package ink.courtpace.index.core.live

/**
 * Parses TML's ATP annual and ongoing CSV snapshots into completed Davis Cup singles rubbers.
 *
 * TML's CSV schema puts the winner first. The parser uses that consistent side order, requires
 * a completed best-of-N result, and refuses missing/inconsistent ace or service-point fields.
 * Raw CSV text is consumed in memory and is never placed in the bundled dataset.
 */
object TmlDavisCupStatsParser {
    private val setScore = Regex("""(\d+)-(\d+)(?:\(\d+\))?""")

    fun parseSeason(snapshots: List<String>, season: Int): List<AtpMatchStats> {
        val matches = linkedMapOf<String, AtpMatchStats>()
        snapshots.forEach snapshotLoop@ { csv ->
            val records = parseCsv(csv)
            if (records.isEmpty()) return@snapshotLoop
            val header = records.first().mapIndexed { index, name -> name.trim().removePrefix("\uFEFF") to index }.toMap()
            val required = listOf(
                "tourney_id", "tourney_name", "tourney_level", "tourney_date", "match_num",
                "best_of", "score", "winner_id", "winner_name", "loser_id", "loser_name",
                "w_ace", "w_svpt", "l_ace", "l_svpt",
            )
            if (required.any { it !in header }) return@snapshotLoop

            records.drop(1).forEach rowLoop@ { row ->
                fun field(name: String): String = row.getOrNull(header[name] ?: -1)?.trim().orEmpty()
                if (field("tourney_level") != "D") return@rowLoop
                if (!field("tourney_name").startsWith("Davis Cup", ignoreCase = true)) return@rowLoop
                if (field("tourney_date").take(4) != season.toString()) return@rowLoop

                val winnerId = field("winner_id")
                val loserId = field("loser_id")
                if (winnerId.isBlank() || loserId.isBlank() || winnerId == loserId) return@rowLoop

                val winner = field("winner_name")
                val loser = field("loser_name")
                if (winner.isBlank() || loser.isBlank()) return@rowLoop
                if (isDoublesName(winner) || isDoublesName(loser)) return@rowLoop

                val score = field("score")
                if (!isCompletedScore(score, field("best_of").toIntOrNull() ?: 3)) return@rowLoop

                val winnerAces = field("w_ace").toIntOrNull() ?: return@rowLoop
                val loserAces = field("l_ace").toIntOrNull() ?: return@rowLoop
                val winnerServicePoints = field("w_svpt").toIntOrNull() ?: return@rowLoop
                val loserServicePoints = field("l_svpt").toIntOrNull() ?: return@rowLoop
                if (
                    winnerServicePoints <= 0 || loserServicePoints <= 0 ||
                    winnerAces < 0 || loserAces < 0 ||
                    winnerAces > winnerServicePoints || loserAces > loserServicePoints
                ) return@rowLoop

                val matchId = "${field("tourney_id")}:${field("match_num")}".takeIf { it != ":" }
                    ?: "${field("tourney_date")}:${winnerId}:${loserId}"
                matches[matchId] = AtpMatchStats(
                    firstPlayer = winner,
                    secondPlayer = loser,
                    firstAces = winnerAces,
                    firstServePoints = winnerServicePoints,
                    secondAces = loserAces,
                    secondServePoints = loserServicePoints,
                    firstWon = true,
                    matchId = matchId,
                )
            }
        }
        return matches.values.toList()
    }

    private fun isDoublesName(value: String): Boolean =
        value.contains(" / ") || value.contains(" & ") || value.contains("/")

    private fun isCompletedScore(score: String, bestOf: Int): Boolean {
        val lower = score.lowercase()
        if (lower.contains("w/o") || lower.contains("walkover") ||
            lower.contains("default") || lower.contains("cancel")
        ) return false
        val sets = setScore.findAll(score).toList()
        if (sets.isEmpty()) return false
        val setsToWin = (bestOf.coerceAtLeast(3) / 2) + 1
        val firstWon = sets.count { it.groupValues[1].toInt() > it.groupValues[2].toInt() }
        val secondWon = sets.count { it.groupValues[2].toInt() > it.groupValues[1].toInt() }
        return firstWon >= setsToWin || secondWon >= setsToWin
    }

    /** Small RFC-4180 reader: quoted commas and escaped quotes are preserved. */
    private fun parseCsv(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
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
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotBlank() }) rows += row
        }
        return rows
    }
}
