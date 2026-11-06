package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.AtpEvents
import ink.courtpace.index.core.live.AtpMatchLinks
import ink.courtpace.index.core.live.AtpStatsParser
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.Tournament
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads completed singles matches from ATP pages on the device and computes the live ace rating.
 *
 * ATP blocks plain HTTP clients for these pages. The on-device WebView is used for the current
 * scores hub, tournament results links and JavaScript-rendered match stats; no guessed match-ID
 * range or third-party API key is required.
 */
class AtpLiveClient(context: Context) {

    private val web = AtpWebReader(context)
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
    ): Result<AceRating.Result> = runCatching {
        val eventKey = "${event.atpId}-${event.year}"
        val cached = store.readAll(eventKey)
        val collected = ConcurrentHashMap(cached)
        val tried = AtomicInteger(0)
        val withContent = AtomicInteger(0)
        val blocked = AtomicBoolean(false)
        val gate = Semaphore(PARALLEL_PAGES)

        val resultsUrl =
            "https://www.atptour.com/en/scores/current/${event.slug}/${event.atpId}/results"
        val source = web.readPageSource(resultsUrl, RESULTS_RENDER_MS).orEmpty()
        val links = AtpMatchLinks.singlesLinks(
            AtpMatchLinks.linksFromResultsPage(source),
        ).filter { link ->
            link.atpId == event.atpId &&
                link.year == event.year &&
                link.section == "archive"
        }
        val pageBytes = source.toByteArray(Charsets.UTF_8).size
        onDiscovery?.invoke(links.size, pageBytes)

        if (links.isEmpty()) {
            val totalStatsLinks = AtpMatchLinks.linksFromResultsPage(source)
                .count { it.atpId == event.atpId && it.year == event.year }
            error(
                "no completed singles stats links on the results page " +
                    "($pageBytes bytes, $totalStatsLinks stats links found); no match pages scanned",
            )
        }

        withTimeoutOrNull(WALK_BUDGET_MS) {
            coroutineScope {
                links.map { link ->
                    async(Dispatchers.Default) {
                        gate.withPermit {
                            if (blocked.get()) return@withPermit
                            if (collected.containsKey(link.id)) {
                                onProgress(collected.size, tried.incrementAndGet())
                                return@withPermit
                            }

                            val attempt = tried.incrementAndGet()
                            onProgress(collected.size, attempt)
                            val url = statsUrl(link)
                            val rendered = web.read(
                                url,
                                timeoutMs = MATCH_RENDER_MS,
                                isReady = { AtpStatsParser.hasStats(it) },
                            )
                            if (rendered != null && rendered.contains(AtpWebReader.BLOCKED, true)) {
                                blocked.set(true)
                                return@withPermit
                            }
                            if (rendered != null && rendered.length > 400) {
                                withContent.incrementAndGet()
                            }
                            val parsed = rendered?.let { AtpStatsParser.parse(it) }
                            if (parsed != null) collected[link.id] = parsed
                            onProgress(collected.size, tried.get())
                        }
                    }
                }.awaitAll()
            }
        }

        // Keep every successful read, even when the walk's internal budget stopped it early.
        if (!blocked.get()) store.writeAll(eventKey, collected)
        val found = collected.values.toList()
        AceRating.rate(found) ?: error(
            when {
                found.isNotEmpty() ->
                    "only ${found.size} completed matches have stats so far " +
                        "(${links.size} completed singles links on the results page)"
                blocked.get() -> "ATP blocked the match-stats page on this device"
                withContent.get() == 0 ->
                    "ATP returned no rendered match stats from ${links.size} completed matches"
                else ->
                    "no parseable ace stats in ${withContent.get()} pages that did load"
            },
        )
    }

    private fun statsUrl(link: AtpMatchLinks.MatchLink): String =
        "https://www.atptour.com/en/scores/stats-centre/${link.section}/" +
            "${link.year}/${link.atpId}/${link.id}"

    companion object {
        const val CURRENT_SCORES_URL = "https://www.atptour.com/en/scores/current"

        /** The event/results page is allowed to render fully before links are collected. */
        const val RESULTS_RENDER_MS = 45_000L

        /** A single match page only needs to wait for its stats to appear. */
        const val MATCH_RENDER_MS = 25_000L

        /** Small bounded WebView pool; large parallel batches are killed by Android. */
        const val PARALLEL_PAGES = 4

        /** Keep whatever has been collected when this internal budget expires. */
        const val WALK_BUDGET_MS = 600_000L
    }
}
