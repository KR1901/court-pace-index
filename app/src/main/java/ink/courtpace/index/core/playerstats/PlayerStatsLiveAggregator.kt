package ink.courtpace.index.core.playerstats

/**
 * Strict CSV reader for TennisMyLife ATP match rows. It handles quoted commas,
 * escaped quotes and embedded line breaks without depending on column position.
 */
object PlayerStatsLiveCsvParser {
    private val requiredColumns = setOf(
        "tourney_id", "surface", "indoor", "tourney_date", "tourney_level", "match_num",
        "winner_id", "winner_name", "winner_ht", "winner_ioc",
        "loser_id", "loser_name", "loser_ht", "loser_ioc", "score",
        "w_SvGms", "w_bpSaved", "w_bpFaced", "l_SvGms", "l_bpSaved", "l_bpFaced",
    )

    fun parse(csv: String): List<Map<String, String>> {
        val records = readRecords(csv)
        require(records.isNotEmpty()) { "ATP CSV is empty" }
        val header = records.first().mapIndexed { index, value ->
            if (index == 0) value.removePrefix("\uFEFF").trim() else value.trim()
        }
        val missing = requiredColumns - header.toSet()
        require(missing.isEmpty()) {
            "ATP CSV is missing required columns: " + missing.sorted().joinToString(", ")
        }
        return records.drop(1).asSequence()
            .filter { row -> row.any { it.isNotBlank() } }
            .map { row ->
                header.mapIndexedNotNull { index, key ->
                    if (key.isBlank()) null else key to row.getOrNull(index).orEmpty().trim()
                }.toMap()
            }
            .toList()
    }

    private fun readRecords(csv: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        while (index < csv.length) {
            val char = csv[index]
            when {
                char == '"' && quoted && index + 1 < csv.length && csv[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row += field.toString()
                    field.setLength(0)
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') {
                        index++
                    }
                    row += field.toString()
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) records += row.toList()
                    row.clear()
                }
                else -> field.append(char)
            }
            index++
        }

        require(!quoted) { "ATP CSV ended inside a quoted field" }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotBlank() }) records += row.toList()
        }
        return records
    }
}

data class PlayerStatsLiveApplyResult(
    val data: PlayerStatsData,
    val addedMatches: Int,
    val matchesWithValidStats: Int,
)

/**
 * Adds newly completed current-season ATP tour-level singles matches to the archive baseline.
 * Stable match keys are shared with tools/build_player_stats.py, so a match is applied once
 * even when it moves from the ongoing CSV to the annual archive CSV.
 */
object PlayerStatsLiveAggregator {
    private val eligibleLevels = setOf("G", "M", "A", "F", "250", "500")
    private val noPlay = Regex(
        """(?i)(?:\bW/O\b|\bW\.O\.\b|\bWO\b|\bWALKOVER\b|\bDEF\b|\bABD\b|\bABN\b|\bABANDONED\b)""",
    )
    private val retirement = Regex("""(?i)\bRET(?:IRED)?\b""")

    fun matchIdentityKey(
        tournamentId: String,
        matchNumber: String,
        firstPlayerId: String,
        secondPlayerId: String,
    ): String {
        require(
            tournamentId.isNotBlank() && matchNumber.isNotBlank() &&
                firstPlayerId.isNotBlank() && secondPlayerId.isNotBlank() &&
                firstPlayerId != secondPlayerId,
        ) { "Match identity requires a tournament, match number and two distinct player IDs" }
        val players = listOf(firstPlayerId, secondPlayerId).sorted()
        return tournamentId + "|" + matchNumber + "|" + players[0] + "|" + players[1]
    }

    fun apply(
        baseline: PlayerStatsData,
        rows: List<Map<String, String>>,
        season: Int,
        updatedAt: String,
    ): PlayerStatsLiveApplyResult {
        val players = baseline.players.associateBy { it.id }.toMutableMap()
        val seenKeys = baseline.meta.currentSeasonMatchKeys.toMutableSet()
        var addedMatches = 0
        var validMatches = 0

        for (row in rows) {
            val tournamentId = row["tourney_id"].orEmpty().trim()
            val tournamentYear = tournamentId.take(4).toIntOrNull()
                ?: row["tourney_date"].orEmpty().take(4).toIntOrNull()
            if (tournamentYear != season) continue
            if (row["tourney_level"].orEmpty().trim().uppercase() !in eligibleLevels) continue

            val winnerId = row["winner_id"].orEmpty().trim()
            val loserId = row["loser_id"].orEmpty().trim()
            val matchNumber = row["match_num"].orEmpty().trim()
            val score = row["score"].orEmpty().trim()
            if (winnerId.isBlank() || loserId.isBlank() || winnerId == loserId ||
                matchNumber.isBlank() || score.isBlank()
            ) continue

            val key = matchIdentityKey(tournamentId, matchNumber, winnerId, loserId)
            if (key in seenKeys) continue
            if (noPlay.containsMatchIn(score)) continue

            val winnerName = row["winner_name"].orEmpty().trim().ifBlank { winnerId }
            val loserName = row["loser_name"].orEmpty().trim().ifBlank { loserId }
            val winnerHeight = validHeight(row["winner_ht"])
            val loserHeight = validHeight(row["loser_ht"])
            val winnerCountry = row["winner_ioc"].orEmpty().trim().ifBlank { null }
            val loserCountry = row["loser_ioc"].orEmpty().trim().ifBlank { null }

            val isRetirement = retirement.containsMatchIn(score)
            val stats = if (isRetirement) null else serviceReturnStats(row)
            val buckets = matchBuckets(row["surface"], row["indoor"])

            var winner = players[winnerId] ?: PlayerStatsPlayer(
                id = winnerId,
                name = winnerName,
                country = winnerCountry,
                heightCm = winnerHeight,
                heightSource = if (winnerHeight != null) "tml-live-match-database" else null,
            )
            var loser = players[loserId] ?: PlayerStatsPlayer(
                id = loserId,
                name = loserName,
                country = loserCountry,
                heightCm = loserHeight,
                heightSource = if (loserHeight != null) "tml-live-match-database" else null,
            )
            winner = winner.copy(
                country = winner.country ?: winnerCountry,
                heightCm = winner.heightCm ?: winnerHeight,
                heightSource = winner.heightSource
                    ?: if (winner.heightCm == null && winnerHeight != null) "tml-live-match-database" else null,
            )
            loser = loser.copy(
                country = loser.country ?: loserCountry,
                heightCm = loser.heightCm ?: loserHeight,
                heightSource = loser.heightSource
                    ?: if (loser.heightCm == null && loserHeight != null) "tml-live-match-database" else null,
            )

            winner = addPlayerMatch(
                winner, season, buckets, won = true,
                servePlayed = stats?.winnerServePlayed ?: 0,
                serveWon = stats?.winnerServeWon ?: 0,
                returnPlayed = stats?.loserServePlayed ?: 0,
                returnWon = stats?.loserBreaksConceded ?: 0,
                hasStats = stats != null,
            )
            loser = addPlayerMatch(
                loser, season, buckets, won = false,
                servePlayed = stats?.loserServePlayed ?: 0,
                serveWon = stats?.loserServeWon ?: 0,
                returnPlayed = stats?.winnerServePlayed ?: 0,
                returnWon = stats?.winnerBreaksConceded ?: 0,
                hasStats = stats != null,
            )
            players[winnerId] = winner
            players[loserId] = loser
            seenKeys += key
            addedMatches++
            if (stats != null) validMatches++
        }

        if (addedMatches == 0) {
            return PlayerStatsLiveApplyResult(baseline, 0, 0)
        }

        val playerRows = players.values.sortedWith(
            compareBy<PlayerStatsPlayer> { it.name.lowercase() }.thenBy { it.id },
        )
        val heightCount = playerRows.count { it.heightCm != null }
        val eligibleArchivePlayerCount = playerRows.count { it.seasons.isNotEmpty() }
        val availableSeasons = (baseline.meta.statsAvailableSeasons +
            if (validMatches > 0) listOf(season) else emptyList()).distinct().sorted()

        val updated = baseline.copy(
            meta = baseline.meta.copy(
                lastSeason = maxOf(baseline.meta.lastSeason, season),
                players = playerRows.size,
                playersWithHeight = heightCount,
                playersWithEligibleArchiveResults = eligibleArchivePlayerCount,
                statsAvailableSeasons = availableSeasons,
                currentSeasonMatchKeys = seenKeys.sorted(),
                liveUpdatedAt = updatedAt,
                liveMatchCount = baseline.meta.liveMatchCount + addedMatches,
            ),
            players = playerRows,
        )
        return PlayerStatsLiveApplyResult(updated, addedMatches, validMatches)
    }

    private data class ServiceReturnStats(
        val winnerServePlayed: Int,
        val winnerServeWon: Int,
        val loserServePlayed: Int,
        val loserServeWon: Int,
        val winnerBreaksConceded: Int,
        val loserBreaksConceded: Int,
    )

    private fun serviceReturnStats(row: Map<String, String>): ServiceReturnStats? {
        val winnerServe = nonNegativeInt(row["w_SvGms"]) ?: return null
        val winnerFaced = nonNegativeInt(row["w_bpFaced"]) ?: return null
        val winnerSaved = nonNegativeInt(row["w_bpSaved"]) ?: return null
        val loserServe = nonNegativeInt(row["l_SvGms"]) ?: return null
        val loserFaced = nonNegativeInt(row["l_bpFaced"]) ?: return null
        val loserSaved = nonNegativeInt(row["l_bpSaved"]) ?: return null
        if (winnerSaved > winnerFaced || loserSaved > loserFaced) return null

        val winnerBreaks = winnerFaced - winnerSaved
        val loserBreaks = loserFaced - loserSaved
        if (winnerBreaks > winnerServe || loserBreaks > loserServe) return null

        return ServiceReturnStats(
            winnerServePlayed = winnerServe,
            winnerServeWon = winnerServe - winnerBreaks,
            loserServePlayed = loserServe,
            loserServeWon = loserServe - loserBreaks,
            winnerBreaksConceded = winnerBreaks,
            loserBreaksConceded = loserBreaks,
        )
    }

    private fun nonNegativeInt(value: String?): Int? {
        val numeric = value?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return null
        if (!numeric.isFinite() || numeric < 0.0 || numeric % 1.0 != 0.0) return null
        return numeric.toInt()
    }

    private fun validHeight(value: String?): Int? =
        nonNegativeInt(value)?.takeIf { it in 140..220 }

    private fun matchBuckets(surfaceValue: String?, indoorValue: String?): List<String> {
        val surface = surfaceValue.orEmpty().trim().lowercase()
        return when (surface) {
            "hard" -> {
                val indoor = when (indoorValue.orEmpty().trim().lowercase()) {
                    "i", "indoor", "yes", "y", "true", "1" -> "hardIndoor"
                    "o", "outdoor", "no", "n", "false", "0" -> "hardOutdoor"
                    else -> "hardUnknown"
                }
                listOf("all", "hardAll", indoor)
            }
            "clay" -> listOf("all", "clay")
            "grass" -> listOf("all", "grass")
            "carpet" -> listOf("all", "carpet")
            else -> listOf("all")
        }
    }

    private fun addPlayerMatch(
        player: PlayerStatsPlayer,
        season: Int,
        buckets: List<String>,
        won: Boolean,
        servePlayed: Int,
        serveWon: Int,
        returnPlayed: Int,
        returnWon: Int,
        hasStats: Boolean,
    ): PlayerStatsPlayer {
        val seasons = player.seasons.associateBy { it.y }.toMutableMap()
        val current = seasons[season] ?: PlayerSeasonStats(y = season)
        val updatedBuckets = current.b.toMutableMap()

        for (bucketId in buckets) {
            val counter = updatedBuckets[bucketId] ?: PlayerStatsBucket()
            updatedBuckets[bucketId] = counter.copy(
                mp = counter.mp + 1,
                w = counter.w + if (won) 1 else 0,
                l = counter.l + if (won) 0 else 1,
                sp = counter.sp + if (hasStats) servePlayed else 0,
                sw = counter.sw + if (hasStats) serveWon else 0,
                rp = counter.rp + if (hasStats) returnPlayed else 0,
                rw = counter.rw + if (hasStats) returnWon else 0,
                sm = counter.sm + if (hasStats) 1 else 0,
            )
        }

        seasons[season] = current.copy(b = updatedBuckets.toSortedMap())
        return player.copy(seasons = seasons.values.sortedBy { it.y })
    }
}
