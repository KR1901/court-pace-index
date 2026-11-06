package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.AtpEvents
import ink.courtpace.index.core.live.AtpMatchLinks
import ink.courtpace.index.core.model.LiveEvent
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
        onProgress: (found: Int, tried: Int) -> Unit = { _, _ -> },
        onDiscovery: ((ids: Int, pageBytes: Int) -> Unit)? = null,
    ): Result<AceRating.Result> {
        return try {
            Result.success(fetchAceRatingInternal(event, onProgress, onDiscovery))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun fetchAceRatingInternal(
        event: AtpEvents.Ref,
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
        val links = AtpMatchLinks.singlesLinks(
            AtpMatchLinks.linksFromResultsPage(source),
        ).filter { link ->
            link.atpId == event.atpId && link.year == event.year
        }.distinctBy { it.id }
        val pageBytes = source.toByteArray(Charsets.UTF_8).size
        onDiscovery?.invoke(links.size, pageBytes)
        val discoveredIds = links.map { it.id }.toSet()
        // Cache entries are scoped by event/year, but only matches still present on the
        // current results page belong in this event's current rating denominator.
        collected.keys.toList().filterNot { it in discoveredIds }.forEach(collected::remove)

        if (links.isEmpty()) {
            val totalStatsLinks = AtpMatchLinks.linksFromResultsPage(source)
                .count { it.atpId == event.atpId && it.year == event.year }
            error(
                "no completed singles stats links on the results page " +
                    "($pageBytes bytes, $totalStatsLinks stats links found); no match lookups attempted",
            )
        }

        withTimeoutOrNull(WALK_BUDGET_MS) {
            coroutineScope {
                links.map { link ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            if (collected.containsKey(link.id)) {
                                onProgress(collected.size, tried.incrementAndGet())
                                return@withPermit
                            }

                            val attempt = tried.incrementAndGet()
                            onProgress(collected.size, attempt)
                            val result = infosys.read(event.year, event.atpId, link.id)
                            if (result.payloadBytes > 0) payloads.incrementAndGet()
                            if (result.stats != null) {
                                collected[link.id] = result.stats
                                failuresByMatch.remove(link.id)
                            } else {
                                val reason = result.diagnostic ?: "Stats Plus returned no usable stats"
                                failuresByMatch[link.id] = reason
                                if (samples.size < MAX_DIAGNOSTIC_SAMPLES) {
                                    samples.add("${link.id}: $reason")
                                }
                            }
                            onProgress(collected.size, tried.get())
                        }
                    }
                }.awaitAll()
            }
        }

        // Persist every successful read, even when a sibling page is blocked or the walk budget
        // expires. The next refresh can then request only the still-missing match IDs instead of
        // downloading already-read pages again.
        store.writeAll(eventKey, collected)
        val found = collected.filterKeys { it in discoveredIds }.values.toList()
        val missingIds = links.map { it.id }.filterNot { it in collected }
        val missingReasons = missingIds.associateWith { id ->
            failuresByMatch[id] ?: "Stats Plus did not return a usable record on this refresh"
        }
        val rating = AceRating.rate(found) ?: error(
            when {
                found.isNotEmpty() ->
                    "only ${found.size} of ${links.size} completed matches have Infosys stats"
                payloads.get() == 0 -> {
                    val sample = samples.peek()?.let { " · ${it}" }.orEmpty()
                    "Infosys returned no usable JSON for ${links.size} completed matches$sample"
                }
                else -> {
                    val sample = samples.peek()?.let { " · ${it}" }.orEmpty()
                    "received ${payloads.get()} Infosys JSON payloads but found no usable " +
                        "set0 ace/service-point rows$sample"
                }
            },
        )
        return rating.copy(
            discoveredMatches = links.size,
            missingMatchIds = missingIds,
            missingReasons = missingReasons,
        )
    }

    companion object {
        const val CURRENT_SCORES_URL = "https://www.atptour.com/en/scores/current"

        /** The event/results page is allowed to render fully before links are collected. */
        const val RESULTS_RENDER_MS = 45_000L

        /** Keep per-match failure examples compact so the live card remains readable. */
        const val MAX_DIAGNOSTIC_SAMPLES = 2

        /** Small bounded WebView pool; large parallel batches are killed by Android. */
        const val PARALLEL_PAGES = 4

        /** Keep whatever has been collected when this internal budget expires. */
        const val WALK_BUDGET_MS = 600_000L
    }
}
