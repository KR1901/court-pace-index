package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.DavisCupStatsParser
import ink.courtpace.index.core.model.LiveSpeedBaseline
import kotlinx.coroutines.CancellationException

/**
 * Live Davis Cup Final 8 stats adapter.
 *
 * Davis Cup is not published on the ATP current-scores / Infosys match-stat route. This client
 * reads completed singles results from TennisRace.ai's public 2026 Davis Cup event page and
 * parses only displayed aces and total service points. It is isolated from AtpLiveClient so ATP
 * tournament discovery and scoring remain unchanged.
 */
class DavisCupLiveClient(context: Context) {

    private val web = AtpWebReader(context)
    private val store = MatchStatsStore(context)

    suspend fun fetchAceRating(
        year: Int,
        baseline: LiveSpeedBaseline,
        onProgress: (found: Int, tried: Int) -> Unit = { _, _ -> },
    ): Result<AceRating.Result> {
        return try {
            require(year == baseline.season) {
                "Davis Cup season baseline does not match the requested season"
            }
            require(year == SUPPORTED_LIVE_SEASON) {
                "Davis Cup live stats source is currently configured for $SUPPORTED_LIVE_SEASON"
            }

            val eventKey = "davis-cup-$year"
            val cached = store.readAll(eventKey)
            // The Davis Cup event page carries the tie schedule; the site homepage's
            // latest-results section carries the individual match-stat links. Read both.
            val eventPage = web.readPageSource(
                EVENT_URL_TEMPLATE.format(year),
                EVENT_PAGE_TIMEOUT_MS,
            ).orEmpty()
            val homePage = web.readPageSource("$BASE_URL/", EVENT_PAGE_TIMEOUT_MS).orEmpty()
            val usableSources = listOf(eventPage, homePage).filter {
                it.isNotBlank() && !it.contains(AtpWebReader.BLOCKED, ignoreCase = true)
            }
            require(usableSources.isNotEmpty()) {
                "Davis Cup event and latest-results pages could not be read"
            }

            val matchUrls = matchUrlsFromSource(usableSources.joinToString("\n"))
            require(matchUrls.isNotEmpty() || cached.isNotEmpty()) {
                "No Davis Cup Final 8 singles match links were found"
            }

            val collected = cached.toMutableMap()
            var tried = 0
            for (url in matchUrls) {
                val id = MATCH_ID.find(url)?.groupValues?.get(1) ?: continue
                if (id in collected) {
                    onProgress(collected.size, ++tried)
                    continue
                }
                tried++
                onProgress(collected.size, tried)
                val page = web.read(
                    url = url,
                    timeoutMs = MATCH_PAGE_TIMEOUT_MS,
                    isReady = { text ->
                        text.contains("Statistics", ignoreCase = true) &&
                            text.contains("Aces", ignoreCase = true)
                    },
                    returnLastOnTimeout = true,
                ).orEmpty()
                val stats = DavisCupStatsParser.parse(page) ?: continue
                collected[id] = stats.copy(matchId = id)
                onProgress(collected.size, tried)
            }

            store.writeAll(eventKey, collected)
            val matches = collected.values.toList()
            val rating = AceRating.rate(matches, baseline)
                ?: error(
                    "Davis Cup has " + matches.size + " cached completed singles matches, " +
                        "but no server side could be opponent-adjusted from the available player profiles",
                )
            Result.success(
                rating.copy(
                    discoveredMatches = maxOf(matchUrls.size, matches.size),
                    totalServerSides = maxOf(matchUrls.size, matches.size) * 2,
                    missingMatchIds = matchUrls.mapNotNull { MATCH_ID.find(it)?.groupValues?.get(1) }
                        .filterNot { it in collected },
                    inProgressMatches = (matchUrls.size - matches.size).coerceAtLeast(0),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    companion object {
        const val SUPPORTED_LIVE_SEASON = 2026
        const val EVENT_URL_TEMPLATE = "https://www.tennisrace.ai/davis-cup-%d"
        const val BASE_URL = "https://www.tennisrace.ai"
        const val EVENT_PAGE_TIMEOUT_MS = 45_000L
        const val MATCH_PAGE_TIMEOUT_MS = 25_000L
        const val MAX_MATCH_PAGES = 32

        private val MATCH_URL = Regex(
            """(?i)(?:https?://www\.tennisrace\.ai)?/match/[0-9]+-[a-z0-9-]*davis-cup-final-8-atp-2026[a-z0-9-]*""",
        )
        private val MATCH_ID = Regex("""/match/([0-9]+)-""")

        /** Filter the latest-results page to Davis Cup Final 8 match-stat links only. */
        internal fun matchUrlsFromSource(source: String): List<String> =
            MATCH_URL.findAll(source)
                .map { it.value.substringBefore("#").substringBefore("?") }
                .map { if (it.startsWith("/")) BASE_URL + it else it }
                .distinct()
                .take(MAX_MATCH_PAGES)
    }
}
