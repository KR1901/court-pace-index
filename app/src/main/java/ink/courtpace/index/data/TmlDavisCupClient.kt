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
        /** Unique identified singles rubbers, including scoreless fixtures and walkovers. */
        val identifiedSingles: Int,
        /** Completed, played singles rubbers with a published result score. */
        val discoveredMatches: Int,
        val matchesWithServeStats: Int,
        val missingStatsMatches: Int,
        /** Scoreless rows have no published result yet; they are not completed missing-stat matches. */
        val scorelessRows: Int,
        /** Walkovers/defaults have a result marker but no played match statistics. */
        val noPlayRows: Int,
        /** Completed score-bearing singles that fail the ace/service-point validation. */
        val malformedStatsMatches: Int,
    )

    private data class CachedAnnual(
        val year: Int,
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
        parseMatchRows(annual, ongoing, year)
    }

    internal fun parseMatchRows(annualCsv: String, ongoingCsv: String, year: Int): MatchBatch {
        // Merge every identified singles rubber first, not only score-bearing rows. The
        // ongoing snapshot is newer and must replace an annual scoreless fixture when it
        // becomes a completed result, otherwise that rubber is counted as both scoreless and completed
        // and completed. The player pair disambiguates same-number rubbers in different ties.
        val uniqueByRubber = linkedMapOf<String, Map<String, String>>()
        listOf(annualCsv, ongoingCsv).forEach { snapshot ->
            parseCsv(snapshot).forEach { row ->
                if (
                    row["tourney_level"] == "D" &&
                    isDavisCupName(row["tourney_name"]) &&
                    row["tourney_date"].orEmpty().take(4) == year.toString() &&
                    row["winner_id"].orEmpty().isNotBlank() &&
                    row["loser_id"].orEmpty().isNotBlank() &&
                    !isDoubles(row)
                ) {
                    uniqueByRubber[matchKey(row)] = row
                }
            }
        }

        val uniqueRows = uniqueByRubber.values.toList()
        val scorelessRows = uniqueRows.filter { it["score"].orEmpty().isBlank() }
        val noPlayRows = uniqueRows.filter { isNoPlayScore(it["score"]) }
        val completedRows = uniqueRows.filter {
            it["score"].orEmpty().isNotBlank() && !isNoPlayScore(it["score"])
        }
        val matches = completedRows.mapNotNull(::toMatchStats)
        val requiredServeStats = listOf("w_ace", "w_svpt", "l_ace", "l_svpt")
        val missingStats = completedRows.count { row ->
            requiredServeStats.any { row[it].isNullOrBlank() }
        }
        val malformedStats = completedRows.count { row ->
            requiredServeStats.none { row[it].isNullOrBlank() } && toMatchStats(row) == null
        }

        return MatchBatch(
            matches = matches,
            identifiedSingles = uniqueRows.size,
            discoveredMatches = completedRows.size,
            matchesWithServeStats = matches.size,
            missingStatsMatches = missingStats,
            scorelessRows = scorelessRows.size,
            noPlayRows = noPlayRows.size,
            malformedStatsMatches = malformedStats,
        )
    }

    private fun annualCsv(year: Int): String {
        val now = System.currentTimeMillis()
        annualCache?.takeIf {
            it.year == year && now - it.fetchedAtMillis < ANNUAL_CACHE_TTL_MS
        }?.let {
            return it.csv
        }
        val csv = requestCsv("$BASE_URL/$year.csv")
        annualCache = CachedAnnual(year, now, csv)
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
            val header = body.substringBefore('\n').removePrefix("\uFEFF")
                .split(',').map { it.trim() }.toSet()
            val required = setOf(
                "tourney_id", "tourney_name", "tourney_level", "tourney_date", "match_num",
                "score", "winner_id", "winner_name", "loser_id", "loser_name",
                "w_ace", "w_svpt", "l_ace", "l_svpt",
            )
            if (body.isBlank() || !header.containsAll(required)) {
                throw IllegalStateException("TML returned an invalid match CSV schema")
            }
            return body
        }
    }

    private fun isDavisCupName(name: String?): Boolean =
        name.orEmpty().lowercase().filter(Char::isLetterOrDigit).startsWith("daviscup")

    private fun isNoPlayScore(score: String?): Boolean {
        val normalized = score.orEmpty().uppercase().replace(Regex("\\s+"), "")
        return normalized.contains("W/O") ||
            normalized == "WO" ||
            normalized.contains("WALKOVER") ||
            normalized.contains("DEF")
    }

    private fun isDoubles(row: Map<String, String>): Boolean {
        val names = listOf(row["winner_name"].orEmpty(), row["loser_name"].orEmpty())
        return names.any { name ->
            name.contains("/") || name.contains(" & ") ||
                name.contains(" and ", ignoreCase = true)
        }
    }

    private fun matchKey(row: Map<String, String>): String {
        val tournament = row["tourney_id"].orEmpty().trim()
        val date = row["tourney_date"].orEmpty().trim()
        val match = row["match_num"].orEmpty().trim()
        if (tournament.isNotEmpty() && date.isNotEmpty() && match.isNotEmpty()) {
            val players = listOf(
                row["winner_id"].orEmpty().trim(),
                row["loser_id"].orEmpty().trim(),
            ).sorted()
            return (listOf("rubber", tournament, date, match) + players).joinToString("|")
        }
        return listOf(
            "fallback", tournament, date,
            row["winner_id"], row["loser_id"],
            row["winner_name"], row["loser_name"],
        ).joinToString("|") { it.orEmpty().trim() }
    }

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
