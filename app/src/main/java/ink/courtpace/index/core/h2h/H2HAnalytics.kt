package ink.courtpace.index.core.h2h

import ink.courtpace.index.core.playerstats.PlayerStatsBucket
import ink.courtpace.index.core.playerstats.PlayerStatsPlayer
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class H2HSurfaceFilter(val label: String, val bucketKey: String) {
    ALL("All", "all"),
    HARD("Hard", "hardAll"),
    OUTDOOR_HARD("Outdoor Hard", "hardOutdoor"),
    INDOOR_HARD("Indoor Hard", "hardIndoor"),
    GRASS("Grass", "grass"),
    CLAY("Clay", "clay"),
}

data class H2HRecord(
    val wins: Int?,
    val losses: Int?,
    val winPercentage: Double?,
    val titles: Int,
    val matches: Int?,
)

data class H2HSetRecord(
    val setsWon: Int?,
    val setsLost: Int?,
    val winPercentage: Double?,
    val setsPlayed: Int,
    val matchesWithScores: Int,
)

object H2HAnalytics {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    private val titleLevels = setOf("G", "M", "A", "F", "250", "500")
    private val nonTitleEvents = listOf(
        "davis cup",
        "united cup",
        "laver cup",
        "atp cup",
        "olympic",
        "hopman cup",
        "world team cup",
        "fed cup",
        "federation cup",
        "billie jean king cup",
        "wightman cup",
    )

    /**
     * Win/loss totals come from the existing, full-history Player Stats buckets. Those buckets
     * are built from the source match CSV including the indoor/outdoor flag, unlike the public
     * player-performance API's Match JSON response. Titles are counted from match-level final
     * records and follow the selected surface; indoor/outdoor hard titles use the same
     * tournament-level source venue flags stored in Player Stats metadata.
     */
    fun record(
        playerId: String,
        playerStats: PlayerStatsPlayer?,
        matches: List<H2HMatch>,
        year: Int?,
        surface: H2HSurfaceFilter,
        tournamentIndoorById: Map<String, String> = emptyMap(),
    ): H2HRecord {
        val buckets = playerStats?.seasons.orEmpty().asSequence()
            .filter { year == null || it.y == year }
            .mapNotNull { it.b[surface.bucketKey] }
            .toList()

        val wins = if (playerStats == null) null else buckets.sumOf(PlayerStatsBucket::w)
        val losses = if (playerStats == null) null else buckets.sumOf(PlayerStatsBucket::l)
        val matchCount = if (playerStats == null) null else buckets.sumOf(PlayerStatsBucket::mp)

        val titles = matches.asSequence()
            .filter { (year == null || it.year == year) && it.winnerId == playerId && isTitleFinal(it) }
            .filter { matchesSelectedSurface(it, surface, tournamentIndoorById) }
            .map { "${it.year ?: 0}|" + it.eventId.ifBlank { it.eventName } }
            .distinct()
            .count()

        val winPercentage = if (wins != null && losses != null && wins + losses > 0) {
            wins.toDouble() * 100.0 / (wins + losses)
        } else {
            null
        }

        return H2HRecord(
            wins = wins,
            losses = losses,
            winPercentage = winPercentage,
            titles = titles,
            matches = matchCount,
        )
    }

    private fun matchesSelectedSurface(
        match: H2HMatch,
        surface: H2HSurfaceFilter,
        tournamentIndoorById: Map<String, String>,
    ): Boolean {
        val matchSurface = match.surface?.lowercase(Locale.ROOT)
        return when (surface) {
            H2HSurfaceFilter.ALL -> true
            H2HSurfaceFilter.HARD -> matchSurface == "hard"
            H2HSurfaceFilter.OUTDOOR_HARD ->
                matchSurface == "hard" && tournamentIndoorById[match.eventId] == "outdoor"
            H2HSurfaceFilter.INDOOR_HARD ->
                matchSurface == "hard" && tournamentIndoorById[match.eventId] == "indoor"
            H2HSurfaceFilter.GRASS -> matchSurface == "grass"
            H2HSurfaceFilter.CLAY -> matchSurface == "clay"
        }
    }

    /**
     * The TML match score is recorded from the winner's perspective (winner first, loser second).
     * Only completed sets are counted: a normal set must be won by two with at least six games,
     * or be 7-6. This also accepts a deciding match tiebreak such as [10-8] as one completed set.
     * A retirement's unfinished final set (for example 3-2 RET) is ignored.
     */
    private val setScorePattern = Regex(
        """(?<!\d)(\d{1,2})\s*-\s*(\d{1,2})(?:\s*\(\s*\d{1,2}(?:\s*-\s*\d{1,2})?\s*\))?"""
    )

    fun completedSetScores(score: String?): List<Pair<Int, Int>> {
        if (score.isNullOrBlank()) return emptyList()
        return setScorePattern.findAll(score).mapNotNull { match ->
            val first = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val second = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val high = maxOf(first, second)
            val low = minOf(first, second)
            val completed = (high >= 6 && high - low >= 2) || (high == 7 && low == 6)
            if (completed) first to second else null
        }.toList()
    }

    /**
     * Aggregates sets across the complete recorded H2H meeting list. The winner-side set score
     * order is mapped back to the selected player by the match winner/loser IDs.
     */
    fun setRecord(playerId: String, meetings: List<H2HMatch>): H2HSetRecord {
        var setsWon = 0
        var setsLost = 0
        var matchesWithScores = 0

        meetings.forEach { match ->
            val completedSets = completedSetScores(match.score)
            if (completedSets.isEmpty()) return@forEach
            val playerIsWinner = playerId == match.winnerId
            val playerIsLoser = playerId == match.loserId
            if (!playerIsWinner && !playerIsLoser) return@forEach

            matchesWithScores += 1
            completedSets.forEach { (winnerGames, loserGames) ->
                if (playerIsWinner) {
                    if (winnerGames > loserGames) setsWon++ else setsLost++
                } else {
                    if (loserGames > winnerGames) setsWon++ else setsLost++
                }
            }
        }

        val played = setsWon + setsLost
        val hasScoreData = played > 0
        return H2HSetRecord(
            setsWon = if (hasScoreData) setsWon else null,
            setsLost = if (hasScoreData) setsLost else null,
            winPercentage = if (hasScoreData) setsWon.toDouble() * 100.0 / played else null,
            setsPlayed = played,
            matchesWithScores = matchesWithScores,
        )
    }

    fun winsAgainst(playerId: String, meetings: List<H2HMatch>): Int =
        meetings.count { it.winnerId == playerId }

    fun isTitleFinal(match: H2HMatch): Boolean {
        if (match.round?.uppercase(Locale.ROOT) !in setOf("F", "FINAL")) return false
        if (match.teamEvent == true || match.level?.uppercase(Locale.ROOT) !in titleLevels) return false
        val name = match.eventName.lowercase(Locale.ROOT)
        return nonTitleEvents.none(name::contains)
    }

    fun age(birthDate: LocalDate?, asOf: LocalDate = LocalDate.now()): Int? {
        if (birthDate == null || birthDate.isAfter(asOf)) return null
        return Period.between(birthDate, asOf).years
    }

    fun formatHeight(heightCm: Int?): String {
        if (heightCm == null || heightCm !in 140..220) return "Unavailable"
        val totalInches = kotlin.math.round(heightCm / 2.54).toInt()
        val feet = totalInches / 12
        val inches = totalInches % 12
        return "${heightCm} cm · ${feet}′${inches}″"
    }

    fun formatDate(date: LocalDate?): String =
        date?.format(dateFormatter) ?: "Date unavailable"
}
