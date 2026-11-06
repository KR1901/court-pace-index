package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.AtpEvents
import ink.courtpace.index.core.live.AtpMatchLinks
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.LiveSpeedBaseline
import ink.courtpace.index.core.model.Tournament
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads completed singles matches from ATP pages on the device and computes the live ace rating.
 *
 * The on-device WebView is used only to read the ATP current-scores/results pages and discover
 * completed singles match IDs. Per-match statistics are read from ATP's Infosys Stats Plus JSON
 * feed; no per-match HTML scraping, guessed match-ID range or third-party API key is required.
 */
class AtpLiveClient(context: Context) {

    private val web = AtpWebReader(context)
    private val infosys = AtpInfosysStatsClient()
    private val store = MatchStatsStore(context)

    /** Discover every currently listed ATP 1000/500/250 event from the ATP current-scores hub. */
    suspend fun discoverEvents(
        tournaments: List<Tournament>,
        fallback: List<LiveEvent>,
        year: Int,
    ): AtpEvents.Discovery {
        val source = web.readPageSource(CURRENT_SCORES_URL)
        val html = source.orEmpty()
        val discovered = AtpEvents.fromCurrentScoresPage(html, tournaments, year)
        val events = discovered.ifEmpty { AtpEvents.fallback(fallback, year) }
        return AtpEvents.Discovery(
            events = events,
            pageBytes = html.toByteArray(Charsets.UTF_8).size,
        )
    }

    /**
     * Rates the event from completed singles match stats linked by its own results page.
     *
     * The previous implementation substituted ms001..ms096 when discovery failed. Those IDs
     * are not a draw-order index: most are nonexistent, and each miss cost another WebView.
     * An unreadable results page now produces an explicit diagnostic and zero match-page scans.
     */
    suspend fun fetchAceRating(
        event: AtpEvents.Ref,
        baseline: LiveSpeedBaseline,
        onProgress: (found: Int, tried: Int) -> Unit = { _, _ -> },
        onDiscovery: ((ids: Int, pageBytes: Int) -> Unit)? = null,
    ): Result<AceRating.Result> {
        return try {
            require(baseline.season == event.year) { "speed baseline season does not match the live event" }
            Result.success(fetchAceRatingInternal(event, baseline, onProgress, onDiscovery))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun fetchAceRatingInternal(
        event: AtpEvents.Ref,
        baseline: LiveSpeedBaseline,
        onProgress: (found: Int, tried: Int) -> Unit,
        onDiscovery: ((ids: Int, pageBytes: Int) -> Unit)?,
    ): AceRating.Result {
        val eventKey = "${event.atpId}-${event.year}"
        val cached = store.readAll(eventKey)
        val collected = ConcurrentHashMap(cached)
        val tried = AtomicInteger(0)
        val payloads = AtomicInteger(0)
        val samples = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val failuresByMatch = ConcurrentHashMap<String, String>()
        val gate = Semaphore(PARALLEL_PAGES)

        val resultsUrl =
            "https://www.atptour.com/en/scores/current/${event.slug}/${event.atpId}/results"
        val source = web.readPageSource(resultsUrl, RESULTS_RENDER_MS).orEmpty()
        // The results page supplies the completed singles match IDs. Match statistics themselves
        // come from ATP/Infosys JSON, not by scraping the visible HTML or guessing per-match URLs.
        val eventSingles = AtpMatchLinks.singlesLinks(
            AtpMatchLinks.linksFromResultsPage(source),
        ).filter { link ->
            link.atpId == event.atpId && link.year == event.year
        }.distinctBy { it.id }
        val archiveLinks = AtpMatchLinks.completedSinglesLinks(eventSingles)
        val archiveIds = archiveLinks.map { it.id }.toSet()
        val inProgressRouteIds = eventSingles
            .filter { it.section == "live" }
            .map { it.id }
            .toSet()

        // No-play decisions are generic outcome data, not hand-maintained match IDs. A W/O
        // marker on the ATP card is authoritative; cached Infosys metadata can also identify
        // a completed result with zero sets and no played-set data.
        val noPlayIds = ConcurrentHashMap.newKeySet<String>()
        noPlayIds.addAll(store.readNoPlayIds(eventKey).intersect(archiveIds))
        archiveLinks.filter { it.resultStatus == AtpMatchLinks.ResultStatus.WALKOVER }
            .forEach { noPlayIds.add(it.id) }
        // If ATP labels a match RET, never retain an old no-play classification for it.
        archiveLinks.filter { it.resultStatus == AtpMatchLinks.ResultStatus.RETIREMENT }
            .forEach { noPlayIds.remove(it.id) }

        val links = archiveLinks.filterNot { it.id in noPlayIds }
        val pageBytes = source.toByteArray(Charsets.UTF_8).size
        onDiscovery?.invoke(links.size, pageBytes)
        val archiveIdsForRating = links.map { it.id }.toSet()

        // Cache entries not linked from the current results page, or now confirmed as walkovers,
        // must not contribute to this event's current rating.
        collected.keys.toList().filterNot { it in archiveIdsForRating }.forEach(collected::remove)

        if (archiveLinks.isEmpty()) {
            val totalStatsLinks = AtpMatchLinks.linksFromResultsPage(source)
                .count { it.atpId == event.atpId && it.year == event.year }
            error(
                "no completed singles stats links on the results page " +
                    "($pageBytes bytes, $totalStatsLinks stats links found, " +
                    "${inProgressRouteIds.size} still live); no match lookups attempted",
            )
        }
        if (links.isEmpty()) {
            error(
                "all ${archiveLinks.size} archived singles links are confirmed walkovers/no-play; " +
                    "there are no played matches to rate yet",
            )
        }

        val inProgressPayloadIds = ConcurrentHashMap.newKeySet<String>()
        val doublesPayloadIds = ConcurrentHashMap.newKeySet<String>()
        withTimeoutOrNull(WALK_BUDGET_MS) {
            coroutineScope {
                links.map { link ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            if (collected.containsKey(link.id) || noPlayIds.contains(link.id)) {
                                onProgress(collected.size, tried.incrementAndGet())
                                return@withPermit
                            }

                            val attempt = tried.incrementAndGet()
                            onProgress(collected.size, attempt)
                            val result = infosys.read(event.year, event.atpId, link.id)
                            if (result.payloadBytes > 0) payloads.incrementAndGet()
                            when {
                                result.stats != null &&
                                    result.state == AtpInfosysStatsClient.MatchDataState.PLAYED -> {
                                    collected[link.id] = result.stats
                                    failuresByMatch.remove(link.id)
                                }
                                result.state == AtpInfosysStatsClient.MatchDataState.NO_PLAYED_DATA &&
                                    link.resultStatus != AtpMatchLinks.ResultStatus.RETIREMENT -> {
                                    noPlayIds.add(link.id)
                                    collected.remove(link.id)
                                    failuresByMatch.remove(link.id)
                                }
                                result.state == AtpInfosysStatsClient.MatchDataState.IN_PROGRESS &&
                                    link.resultStatus != AtpMatchLinks.ResultStatus.RETIREMENT -> {
                                    inProgressPayloadIds.add(link.id)
                                    collected.remove(link.id)
                                }
                                result.state == AtpInfosysStatsClient.MatchDataState.DOUBLES -> {
                                    doublesPayloadIds.add(link.id)
                                    collected.remove(link.id)
                                }
                                else -> {
                                    val reason = when {
                                        link.resultStatus == AtpMatchLinks.ResultStatus.RETIREMENT &&
                                            result.state == AtpInfosysStatsClient.MatchDataState.NO_PLAYED_DATA ->
                                            "ATP result card marks retirement, but Stats Plus returned no played-set statistics"
                                        link.resultStatus == AtpMatchLinks.ResultStatus.RETIREMENT &&
                                            result.state == AtpInfosysStatsClient.MatchDataState.IN_PROGRESS ->
                                            "ATP result card marks retirement, but Stats Plus has not marked the match completed"
                                        else -> result.diagnostic ?: "Stats Plus returned no usable stats"
                                    }
                                    failuresByMatch[link.id] = reason
                                    if (samples.size < MAX_DIAGNOSTIC_SAMPLES) {
                                        samples.add("${link.id}: $reason")
                                    }
                                }
                            }
                            onProgress(collected.size, tried.get())
                        }
                    }
                }.awaitAll()
            }
        }

        // Persist good match stats and generic no-play outcomes independently. A known walkover
        // is not re-requested every five minutes; a retirement stays eligible for stats fetching.
        store.writeAll(eventKey, collected)
        store.writeNoPlayIds(eventKey, noPlayIds.toSet())

        val inProgressMatches = inProgressRouteIds.size + inProgressPayloadIds.size
        val walkoversExcluded = noPlayIds.count { it in archiveIds }
        val eligibleIds = archiveIdsForRating - noPlayIds - inProgressPayloadIds - doublesPayloadIds
        val found = collected.filterKeys { it in eligibleIds }.values.toList()
        val missingIds = eligibleIds.filterNot { collected.containsKey(it) }
        val missingReasons = missingIds.associateWith { id ->
            failuresByMatch[id] ?: "Stats Plus did not return a usable record on this refresh"
        }
        val adjustmentCoverage = AceRating.coverage(found, baseline)
        val rating = AceRating.rate(found, baseline) ?: error(
            when {
                found.isNotEmpty() -> {
                    "usable Infosys stats for ${found.size}/${eligibleIds.size} played singles matches; " +
                        "opponent adjustment matched ${adjustmentCoverage.adjustedServerSides}/" +
                        "${adjustmentCoverage.totalServerSides} server sides " +
                        "(serve profiles ${adjustmentCoverage.serverProfilesMatched}/" +
                        "${adjustmentCoverage.totalServerSides}, returner profiles " +
                        "${adjustmentCoverage.returnerProfilesMatched}/" +
                        "${adjustmentCoverage.totalServerSides}); at least " +
                        "${AceRating.MIN_ADJUSTED_SERVER_SIDES} adjusted server sides are required"
                }
                payloads.get() == 0 -> {
                    val sample = samples.peek()?.let { " · ${it}" }.orEmpty()
                    "Infosys returned no usable JSON for ${eligibleIds.size} played singles matches$sample"
                }
                else -> {
                    val sample = samples.peek()?.let { " · ${it}" }.orEmpty()
                    "received ${payloads.get()} Infosys JSON payloads but found no usable " +
                        "full-match or played-set ace/service-point rows$sample"
                }
            },
        )
        return rating.copy(
            discoveredMatches = eligibleIds.size,
            // The denominator tracks every eligible completed singles match discovered on
            // ATP (two possible server sides each), not only matches whose Stats Plus payload
            // happened to arrive. The separate matches/discoveredMatches fraction exposes
            // missing match-stat payloads without shrinking the event denominator.
            totalServerSides = eligibleIds.size * 2,
            missingMatchIds = missingIds,
            missingReasons = missingReasons,
            inProgressMatches = inProgressMatches,
            walkoversExcluded = walkoversExcluded,
        )

    }

    companion object {
        const val CURRENT_SCORES_URL = "https://www.atptour.com/en/scores/current"

        /** The event/results page is allowed to render fully before links are collected. */
        const val RESULTS_RENDER_MS = 45_000L

        /** Keep per-match failure examples compact so the live card remains readable. */
        const val MAX_DIAGNOSTIC_SAMPLES = 2

        /**
         * Bound Stats Plus reads to two matches at a time. Combined with per-endpoint retries,
         * this avoids sending the whole tournament batch as a burst to Infosys.
         */
        const val PARALLEL_PAGES = 2

        /** Keep whatever has been collected when this internal budget expires. */
        const val WALK_BUDGET_MS = 600_000L
    }
}
