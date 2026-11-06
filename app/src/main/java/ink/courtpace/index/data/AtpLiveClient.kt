package ink.courtpace.index.data

import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.AtpResultsParser
import ink.courtpace.index.core.live.AtpStatsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the ATP's public results page for a live event.
 *
 * The page is server-rendered, so no browser and no API key are involved. What it publishes is
 * every completed match with its round and score - not ace counts, which the site only shows
 * inside its JavaScript stats view. So what comes back is a results-derived pace reading, and
 * it is labelled as such wherever it appears.
 */
class AtpLiveClient(context: android.content.Context) {

    private val web = AtpWebReader(context)
    private val store = MatchStatsStore(context)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    /**
     * Reads every completed match's stats page for the event and turns them into an ace rating.
     *
     * Each result on the ATP page opens its own stats page at
     * /scores/stats-centre/archive/<year>/<event>/<matchId>, and those pages carry the ace
     * counts the results page omits. Match ids run ms001 upward, so they are walked until a run
     * of misses shows the draw has ended.
     */
    suspend fun fetchAceRating(
        atpId: Int,
        year: Int = 2026,
        onProgress: (found: Int, tried: Int) -> Unit = { _, _ -> },
        onDiscovery: ((ids: Int, pageBytes: Int) -> Unit)? = null,
    ): Result<AceRating.Result> = runCatching {
        val eventKey = "$atpId-$year"
        val cached = store.readAll(eventKey)
        // Keyed by match id: a re-read must replace rather than add, and the same map is what
        // gets persisted for the next refresh.
        val collected = java.util.concurrent.ConcurrentHashMap<String, ink.courtpace.index.core.live.AtpMatchStats>(cached)
        val tried = java.util.concurrent.atomic.AtomicInteger(0)
        val withContent = java.util.concurrent.atomic.AtomicInteger(0)
        var blocked = false
        val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL_PAGES)

        // Ask the event page which matches exist before reading any of them.
        val slug = EVENT_SLUGS[atpId] ?: error("no results page known for event $atpId")
        val resultsUrl = "https://www.atptour.com/en/scores/current/$slug/$atpId/results"
        val resultsHtml = kotlinx.coroutines.withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            get(resultsUrl)
        }
        var discovered = resultsHtml
            ?.let { ink.courtpace.index.core.live.AtpMatchLinks.singles(ink.courtpace.index.core.live.AtpMatchLinks.fromResultsPage(it)) }
            .orEmpty()
        if (discovered.isEmpty()) {
            // The links live in the markup, which innerText does not contain, so they are read
            // from the DOM directly rather than from the flattened page.
            discovered = ink.courtpace.index.core.live.AtpMatchLinks.singles(
                ink.courtpace.index.core.live.AtpMatchLinks.fromResultsPage(
                    web.readLinks(resultsUrl).joinToString("\n"),
                ),
            )
        }
        onDiscovery?.invoke(discovered.size, resultsHtml?.length ?: 0)

        val ids = if (discovered.isNotEmpty()) discovered
        else (1..MAX_MATCHES).map { "ms" + it.toString().padStart(3, '0') }

        // A budget inside the walk, not around it: if it runs out we still rate the matches that
        // were read. Cancelling from outside used to discard them, which is why the count fell
        // from fourteen to six to two as each release added per-id work.
        kotlinx.coroutines.withTimeoutOrNull(WALK_BUDGET_MS) {
            kotlinx.coroutines.coroutineScope {
            ids.map { id ->
                async(Dispatchers.Default) {
                    gate.withPermit {
                        if (blocked) return@withPermit
                        if (cached.containsKey(id)) {
                            onProgress(collected.size, tried.incrementAndGet())
                            return@withPermit
                        }
                        // Count the attempt as it starts, so the row shows page 1 rather than a
                        // "page 0" that means nothing and sits there until the first page ends.
                        onProgress(collected.size, tried.incrementAndGet())
                        val url = "https://www.atptour.com/en/scores/stats-centre/archive/$year/$atpId/$id"

                        // A plain request costs a fraction of a second and answers two things:
                        // whether this match id exists at all, and whether the page happens to
                        // carry its numbers server-side. Only pages that pass both tests are
                        // worth a browser, and the rest are skipped instead of waiting out a
                        // render timeout - that wait, on every non-existent id, is what kept the
                        // count at thirteen.
                        val (status, shell) = probe(url)
                        // Only a page that does not exist is safe to skip. An earlier version
                        // also skipped when the raw HTML title looked wrong, but that title is
                        // generic until JavaScript sets it - so real matches were being thrown
                        // away, which is why the count fell from fourteen to six.
                        if (status == 404) {
                            return@withPermit
                        }
                        if (shell != null && shell.contains(AtpWebReader.BLOCKED, true)) {
                            blocked = true
                            return@withPermit
                        }
                        var parsed = shell?.let { AtpStatsParser.parse(AtpResultsParser.toText(it)) }
                        if (parsed == null) {
                            // The numbers are injected by the page's own JavaScript, so the
                            // remaining pages have to be rendered, not merely fetched.
                            val rendered = web.read(url) { AtpStatsParser.hasStats(it) }
                            if (rendered != null && rendered.contains(AtpWebReader.BLOCKED, true)) {
                                blocked = true
                                return@withPermit
                            }
                            if (rendered != null && rendered.length > 400) withContent.incrementAndGet()
                            parsed = rendered?.let { AtpStatsParser.parse(AtpResultsParser.toText(it)) }
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
    }.let { result ->
        // Whatever happens inside, the caller gets an answer rather than an eternal wait.
        result
    }

    /**
     * A plain GET, always off the main thread.
     *
     * This was a blocking call invoked from a coroutine running on Main, so Android threw
     * NetworkOnMainThreadException, the catch below swallowed it, and the function returned null
     * - silently, every time. That is why discovery always reported "0B page" and never found a
     * single link, while the WebView path kept working because it loads asynchronously.
     */
    private suspend fun get(url: String): String? = probe(url).second

    /**
     * Status code and body. The status is what distinguishes "this match does not exist" from
     * "this page needs rendering", and only the first is safe to skip.
     */
    private suspend fun probe(url: String): Pair<Int, String?> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // Short on purpose: this runs for the results page and for every candidate match,
            // so a slow host multiplies across the whole walk. Twelve plus fifteen seconds was
            // minutes of budget spent waiting on pages that were never going to answer.
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

    private companion object {
        /** A Masters draw is at most 96 matches; walking past that is pointless. */
        /** A Masters draw is at most 96 matches, so the whole draw is covered. */
        const val MAX_MATCHES = 96

        /** Event id to URL slug, for events the live feed can carry. */
        val EVENT_SLUGS = mapOf(352 to "paris")

        /** Reading the event page must not eat the walk's budget. */
        const val DISCOVERY_TIMEOUT_MS = 25_000L

        /** Pages rendered at once. Serial reads were the reason nothing appeared for minutes. */
        const val PARALLEL_PAGES = 12

        /** The walk's own budget. Whatever it has by then is rated rather than discarded. */
        const val WALK_BUDGET_MS = 420_000L

    }

    suspend fun fetch(atpId: Int, slug: String): Result<AtpResultsParser.LivePace> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("https://www.atptour.com/en/scores/current/$slug/$atpId/results")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "text/html")
                    instanceFollowRedirects = true
                }
                var html: String? = null
                var failure: String? = null
                try {
                    if (connection.responseCode in 200..299) {
                        html = connection.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        failure = "ATP returned ${connection.responseCode}"
                    }
                } catch (error: Exception) {
                    failure = error.message ?: "could not reach the ATP results page"
                } finally {
                    connection.disconnect()
                }

                var matches = html?.let { AtpResultsParser.parse(AtpResultsParser.toText(it)) }.orEmpty()
                if (matches.isEmpty()) {
                    // Some pages only fill in after their scripts run; render as a fallback.
                    val rendered = web.read(url.toString())
                    if (rendered != null) {
                        matches = AtpResultsParser.parse(AtpResultsParser.toText(rendered))
                    }
                }
                AtpResultsParser.pace(matches)
                    ?: error(failure ?: "no completed matches on the results page")
            }
        }
}
