package ink.courtpace.index.data

import ink.courtpace.index.core.live.AtpMatchStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads the current-season TML ATP match archive plus its ongoing-tournament supplement.
 *
 * TML's CSV is used only in memory for non-commercial analytical calculations. No match rows
 * are persisted to the app dataset or local cache. The published APK contains only the aggregate
 * event rating and coverage diagnostics.
 */
internal class TmlDavisCupClient {

    data class MatchBatch(
        val matches: List<AtpMatchStats>,
        /** Unique Davis Cup singles rubbers, including those with unpublished serve statistics. */
        val discoveredMatches: Int,
        val matchesWithServeStats: Int,
        val missingStatsMatches: Int,
    )

    private data class CachedAnnual(
        val fetchedAtMillis: Long,
        val csv: String,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var annualCache: CachedAnnual? = null

    suspend fun fetch(year: Int): MatchBatch = withContext(Dispatchers.IO) {
        require(year in 2020..2035) { "Unsupported TML Davis Cup season: $year" }
        val annual = annualCsv(year)
        // This feed changes during the day; unlike the annual archive it is fetched every poll.
        val ongoing = requestCsv(ONGOING_URL)
        val allRows = parseCsv(annual) + parseCsv(ongoing)
        val uniqueRows = allRows.asSequence()
            .filter { row ->
                row["tourney_level"] == "D" &&
                    isDavisCupName(row["tourney_name"]) &&
                    row["tourney_date"].orEmpty().take(4) == year.toString() &&
                    !isDoubles(row)
            }
            .distinctBy(::matchKey)
            .toList()

        val matches = uniqueRows.mapNotNull(::toMatchStats)
        MatchBatch(
            matches = matches,
            discoveredMatches = uniqueRows.size,
            matchesWithServeStats = matches.size,
            missingStatsMatches = (uniqueRows.size - matches.size).coerceAtLeast(0),
        )
    }

    private fun annualCsv(year: Int): String {
        val now = System.currentTimeMillis()
        annualCache?.takeIf { now - it.fetchedAtMillis < ANNUAL_CACHE_TTL_MS }?.let {
            return it.csv
        }
        val csv = requestCsv("$BASE_URL/$year.csv")
        annualCache = CachedAnnual(now, csv)
        return csv
    }

    private fun requestCsv(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/csv")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("TML request failed: HTTP ${response.code} for ${url.substringAfterLast('/')}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank() || !body.substringBefore('\n').contains("tourney_id") ||
                !body.substringBefore('\n').contains("w_svpt")
            ) {
                throw IllegalStateException("TML returned an invalid match CSV")
            }
            return body
        }
    }

    private fun isDavisCupName(name: String?): Boolean =
        name.orEmpty().lowercase().filter(Char::isLetterOrDigit).startsWith("daviscup")

    private fun isDoubles(row: Map<String, String>): Boolean {
        val names = listOf(row["winner_name"].orEmpty(), row["loser_name"].orEmpty())
        return names.any { name ->
            name.contains("/") || name.contains(" & ") ||
                name.contains(" and ", ignoreCase = true)
        }
    }

    private fun matchKey(row: Map<String, String>): String = listOf(
        row["tourney_id"], row["tourney_date"], row["match_num"],
        row["winner_id"], row["loser_id"], row["score"],
    ).joinToString("|") { it.orEmpty().trim() }

    private fun toMatchStats(row: Map<String, String>): AtpMatchStats? {
        val winner = row["winner_name"]?.trim().orEmpty()
        val loser = row["loser_name"]?.trim().orEmpty()
        if (winner.isBlank() || loser.isBlank() || winner.equals(loser, ignoreCase = true)) return null
        val winnerAces = row["w_ace"]?.toIntOrNull() ?: return null
        val loserAces = row["l_ace"]?.toIntOrNull() ?: return null
        val winnerServicePoints = row["w_svpt"]?.toIntOrNull() ?: return null
        val loserServicePoints = row["l_svpt"]?.toIntOrNull() ?: return null
        if (
            winnerAces < 0 || loserAces < 0 ||
            winnerServicePoints <= 0 || loserServicePoints <= 0 ||
            winnerAces > winnerServicePoints || loserAces > loserServicePoints
        ) return null

        val id = matchKey(row).ifBlank { null }
        return AtpMatchStats(
            firstPlayer = winner,
            secondPlayer = loser,
            firstAces = winnerAces,
            firstServePoints = winnerServicePoints,
            secondAces = loserAces,
            secondServePoints = loserServicePoints,
            firstWon = true,
            matchId = id,
        )
    }

    /** Small RFC-4180 CSV reader; supports quoted commas, escaped quotes and multiline cells. */
    private fun parseCsv(input: String): List<Map<String, String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0

        fun finishCell() {
            row += cell.toString()
            cell.setLength(0)
        }
        fun finishRow() {
            finishCell()
            if (row.any { it.isNotBlank() }) records += row
            row = mutableListOf()
        }

        while (index < input.length) {
            val ch = input[index]
            when {
                ch == '"' && quoted && index + 1 < input.length && input[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> finishCell()
                (ch == '\n' || ch == '\r') && !quoted -> {
                    finishRow()
                    if (ch == '\r' && index + 1 < input.length && input[index + 1] == '\n') index++
                }
                else -> cell.append(ch)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
        if (records.isEmpty()) return emptyList()

        val headers = records.first().mapIndexed { index, header ->
            if (index == 0) header.removePrefix("\uFEFF").trim() else header.trim()
        }
        return records.drop(1).map { values ->
            headers.indices.associate { column ->
                headers[column] to values.getOrElse(column) { "" }.trim()
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://stats.tennismylife.org/data"
        const val ONGOING_URL = "$BASE_URL/ongoing_tourneys.csv"
        const val ANNUAL_CACHE_TTL_MS = 30 * 60 * 1_000L
        const val USER_AGENT = "CourtPaceIndex/1.0 (non-commercial research)"
    }
}
