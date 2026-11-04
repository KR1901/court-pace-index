package ink.courtpace.index.data

import ink.courtpace.index.core.live.AtpResultsParser
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
