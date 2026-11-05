package ink.courtpace.index.data

import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.AtpResultsParser
import ink.courtpace.index.core.live.AtpStatsParser
import kotlinx.coroutines.Dispatchers
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
    ): Result<AceRating.Result> = runCatching {
        val found = mutableListOf<ink.courtpace.index.core.live.AtpMatchStats>()
        var tried = 0
        // Match ids are sparse: ms019, ms030 and ms031 exist while ms001..ms008 may not. An
        // early stop on a run of misses gave up before reaching any of them, which is why the
        // rating never appeared even once the fetch was running.
        for (n in 1..MAX_MATCHES) {
            tried = n
            onProgress(found.size, tried)
            val id = "ms" + n.toString().padStart(3, '0')
            val url = "https://www.atptour.com/en/scores/stats-centre/archive/$year/$atpId/$id"
            // The numbers are injected by the page's own JavaScript, so it has to be rendered,
            // not merely fetched: a plain GET returns the empty shell.
            val rendered = web.read(url) { AtpStatsParser.hasStats(it) }
            val stats = rendered?.let { AtpStatsParser.parse(AtpResultsParser.toText(it)) }
            if (stats != null) found += stats
        }
        AceRating.rate(found) ?: error(
            if (found.isEmpty()) "no ATP stats pages readable yet"
            else "only ${found.size} matches have stats so far",
        )
    }

    private fun get(url: String): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html")
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            connection.disconnect()
        }
    } catch (error: Exception) {
        null
    }

    private companion object {
        /** A Masters draw is at most 96 matches; walking past that is pointless. */
        const val MAX_MATCHES = 80
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
