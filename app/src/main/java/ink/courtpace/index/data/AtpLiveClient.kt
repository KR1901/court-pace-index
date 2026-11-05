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
class AtpLiveClient {

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
    suspend fun fetchAceRating(atpId: Int, year: Int = 2026): Result<AceRating.Result> =
        withContext(Dispatchers.IO) {
            runCatching {
                val found = mutableListOf<ink.courtpace.index.core.live.AtpMatchStats>()
                var misses = 0
                for (n in 1..MAX_MATCHES) {
                    if (misses >= MISS_LIMIT) break
                    val id = "ms" + n.toString().padStart(3, '0')
                    val text = get("https://www.atptour.com/en/scores/stats-centre/archive/$year/$atpId/$id")
                    val stats = text?.let { AtpStatsParser.parse(AtpResultsParser.toText(it)) }
                    if (stats != null) {
                        found += stats
                        misses = 0
                    } else {
                        misses++
                    }
                }
                AceRating.rate(found) ?: error(
                    if (found.isEmpty()) "the ATP stats pages for this event are still empty"
                    else "only ${found.size} matches have stats so far",
                )
            }
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
        const val MAX_MATCHES = 96

        /** Stop once this many ids in a row return nothing. */
        const val MISS_LIMIT = 6
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
                try {
                    if (connection.responseCode !in 200..299) {
                        error("ATP returned ${connection.responseCode}")
                    }
                    val html = connection.inputStream.bufferedReader().use { it.readText() }
                    val matches = AtpResultsParser.parse(AtpResultsParser.toText(html))
                    AtpResultsParser.pace(matches) ?: error("no completed matches on the page")
                } finally {
                    connection.disconnect()
                }
            }
        }
}
