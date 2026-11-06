package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.AtpMatchLinks
import ink.courtpace.index.core.live.AtpResultsParser
import ink.courtpace.index.core.live.AtpStatsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the ATP's own pages for the event in progress and rates it.
 *
 * Two facts about this site shape everything here:
 *
 * - The per-match stats pages carry the ace counts, but they are rendered client-side, so they
 *   have to be rendered rather than fetched.
 * - A plain request to this host does not succeed from a phone. The diagnostic reported a
 *   0-byte page on every attempt. The renderer works, so the renderer is the primary path and
 *   the plain request is the fallback, not the other way round.
 */
class AtpLiveClient(context: Context) {

    private val web = AtpWebReader(context)
    private val store = MatchStatsStore(context)
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    /**
     * Rates the event from the ace counts on its match pages.
     *
     * Whatever has been read when the budget expires is rated rather than discarded: a partial
     * figure with an honest count beats no figure at all.
     */
    suspend fun fetchAceRating(
        atpId: Int,
        year: Int = 2026,
        onProgress: (found: Int, tried: Int) -> Unit = { _, _ -> },
        onDiscovery: ((ids: Int, pageBytes: Int) -> Unit)? = null,
    ): Result<AceRating.Result> = runCatching {
        val eventKey = "$atpId-$year"
        val cached = store.readAll(eventKey)
        val collected = java.util.concurrent.ConcurrentHashMap(cached)
        val tried = java.util.concurrent.atomic.AtomicInteger(0)
        val withContent = java.util.concurrent.atomic.AtomicInteger(0)
        var blocked = false
        val gate = Semaphore(PARALLEL_PAGES)

        val slug = EVENT_SLUGS[atpId] ?: error("no results page known for event $atpId")
        val resultsUrl = "https://www.atptour.com/en/scores/current/$slug/$atpId/results"

        // The event page lists every match; its links are the authoritative ids.
        var discovered = AtpMatchLinks.singles(
            AtpMatchLinks.fromResultsPage(get(resultsUrl).orEmpty()),
        )
        if (discovered.isEmpty()) {
            discovered = AtpMatchLinks.singles(
                AtpMatchLinks.fromResultsPage(web.readLinks(resultsUrl).joinToString("\n")),
            )
        }
        onDiscovery?.invoke(discovered.size, 0)

        val ids = discovered.ifEmpty {
            (1..MAX_MATCHES).map { "ms" + it.toString().padStart(3, '0') }
        }

        withTimeoutOrNull(WALK_BUDGET_MS) {
            coroutineScope {
                ids.map { id ->
                    async(Dispatchers.Default) {
                        gate.withPermit {
                            if (blocked) return@withPermit
                            if (cached.containsKey(id)) {
                                onProgress(collected.size, tried.incrementAndGet())
                                return@withPermit
                            }
                            onProgress(collected.size, tried.incrementAndGet())
                            val url = statsUrl(year, atpId, id)

                            // Only a page that does not exist is worth skipping. Skipping on
                            // anything else threw away real matches: the raw title is generic
                            // until the page's own scripts set it.
                            val (status, shell) = probe(url)
                            if (status == 404) return@withPermit
                            if (shell != null && shell.contains(AtpWebReader.BLOCKED, true)) {
                                blocked = true
                                return@withPermit
                            }

                            var parsed = shell?.let { AtpStatsParser.parse(AtpResultsParser.toText(it)) }
                            if (parsed == null) {
                                val rendered = web.read(url, isReady = { AtpStatsParser.hasStats(it) })
                                if (rendered != null && rendered.contains(AtpWebReader.BLOCKED, true)) {
                                    blocked = true
                                    return@withPermit
                                }
                                if (rendered != null && rendered.length > 400) {
                                    withContent.incrementAndGet()
                                }
                                parsed = rendered?.let {
                                    AtpStatsParser.parse(AtpResultsParser.toText(it))
                                }
                            }
                            parsed?.let { collected[id] = it }
                            onProgress(collected.size, tried.get())
                        }
                    }
                }.awaitAll()
            }
        }

        val found = collected.values.toList()
        if (!blocked) store.writeAll(eventKey, collected)
        AceRating.rate(found) ?: error(
            when {
                found.isNotEmpty() -> "only ${found.size} matches have stats so far"
                withContent.get() == 0 -> "the ATP pages came back empty"
                else -> "no stats in ${withContent.get()} pages that did load"
            },
        )
    }

    /** The event's results page: every completed match with score and duration, in one page. */
    suspend fun fetch(atpId: Int, slug: String): Result<AtpResultsParser.LivePace> = runCatching {
        val url = "https://www.atptour.com/en/scores/current/$slug/$atpId/results"
        // A single page, so it gets a generous deadline: the results page is heavy and was
        // being abandoned at the twelve-second default before it finished rendering.
        // Settle, not first-match: the page fills in progressively, and returning early is
        // what produced a "complete" reading of six matches.
        var matches = web.read(
            url,
            timeoutMs = RESULTS_RENDER_MS,
            isReady = { it.contains("wins the match", ignoreCase = true) },
            settle = true,
        )
            ?.let { AtpResultsParser.parse(AtpResultsParser.toText(it)) }
            .orEmpty()
        if (matches.isEmpty()) {
            get(url)?.let { html ->
                matches = AtpResultsParser.parse(AtpResultsParser.toText(html))
            }
        }
        AtpResultsParser.pace(matches) ?: error("no completed matches on the results page")
    }

    private fun statsUrl(year: Int, atpId: Int, matchId: String) =
        "https://www.atptour.com/en/scores/stats-centre/archive/$year/$atpId/$matchId"

    private suspend fun get(url: String): String? = probe(url).second

    /**
     * Status code and body, always off the main thread.
     *
     * The status distinguishes "this match does not exist" from "this page needs rendering", and
     * only the first is safe to skip. Running this on the caller's dispatcher - which was Main -
     * threw NetworkOnMainThreadException, and the catch below turned it into a silent null.
     */
    private suspend fun probe(url: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html")
                instanceFollowRedirects = true
            }
            try {
                val status = connection.responseCode
                val body = if (status in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else null
                status to body
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            0 to null
        }
    }

    companion object {
        /** Event id to URL slug, for events the live feed can carry. */
        val EVENT_SLUGS = mapOf(352 to "paris")

        /** A Masters draw is at most 96 matches, so the whole draw is covered. */
        const val MAX_MATCHES = 96

        /** Pages rendered at once. */
        /**
         * Simultaneous renders. Twelve was chosen to bound the walk and did the opposite: a phone
         * cannot keep that many WebViews alive, so they failed and were counted as "no stats".
         * The count tracked this number rather than any code path - eight concurrent, twenty
         * seconds each, found fourteen matches; twelve concurrent, twelve seconds, found six.
         */
        const val PARALLEL_PAGES = 4

        /** The walk's own budget; whatever it has by then is rated, not discarded. */
        /** Generous on purpose: a complete figure matters more than a fast one here. */
        const val WALK_BUDGET_MS = 600_000L

        /** The results page is one render, so it can afford a long deadline. */
        const val RESULTS_RENDER_MS = 45_000L
    }
}
