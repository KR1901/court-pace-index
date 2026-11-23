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
    val tiebreaksWon: Int?,
    val tiebreaksLost: Int?,
    val tiebreakWinPercentage: Double?,
    val setsPlayed: Int,
    val tiebreaksPlayed: Int,
    val matchesWithScores: Int,
    val matchesWithCompletedSets: Int,
)

object H2HAnalytics {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    private val titleLevels = setOf("G", "M", "A", "F", "250", "500")
    private val qualifyingRounds = setOf("Q1", "Q2", "Q3", "Q4")
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

    /**
     * YTD/Career set statistics must match the Player Stats ATP Tour archive population.
     * That archive includes main-draw tour-level results only; qualifying rounds and
     * Challenger/other levels must not silently inflate these score-derived totals.
     * The H2H rivalry card keeps its default false and follows the exact meeting list.
     */
    private fun isEligibleTourMatch(match: H2HMatch): Boolean {
        val level = match.level?.uppercase(Locale.ROOT) ?: return false
        val round = match.round?.uppercase(Locale.ROOT).orEmpty()
        return level in titleLevels && round !in qualifyingRounds
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
     * TML score strings list the match winner's set score first and the loser's second.
     * Normal sets are counted only when complete. Square-bracketed match tiebreaks (for
     * example [10-8]) count as one set and one tiebreak. A retirement's unfinished set is
     * ignored while completed sets before it remain eligible.
     */
    private data class ParsedSetScore(
        val firstGames: Int,
        val secondGames: Int,
        val isTiebreak: Boolean,
    )

    private val setScorePattern = Regex(
        """(?:\[(\d{1,2})-(\d{1,2})\]|(?<!\d)(\d{1,2})-(\d{1,2})(?:\((\d{1,2})(?:-\d{1,2})?\))?)"""
    )

    private fun parseSetScores(score: String?): List<ParsedSetScore> {
        if (score.isNullOrBlank()) return emptyList()
        return setScorePattern.findAll(score).mapNotNull { match ->
            val isMatchTiebreak = match.groupValues[1].isNotEmpty()
            val first = (if (isMatchTiebreak) match.groupValues[1] else match.groupValues[3]).toIntOrNull()
                ?: return@mapNotNull null
            val second = (if (isMatchTiebreak) match.groupValues[2] else match.groupValues[4]).toIntOrNull()
                ?: return@mapNotNull null
            val high = maxOf(first, second)
            val low = minOf(first, second)
            val completed = if (isMatchTiebreak) {
                high >= 10 && high - low >= 2
            } else {
                (high >= 6 && high - low >= 2) || (high == 7 && low == 6)
            }
            if (!completed) return@mapNotNull null
            ParsedSetScore(
                firstGames = first,
                secondGames = second,
                isTiebreak = isMatchTiebreak || (high == 7 && low == 6),
            )
        }.toList()
    }

    fun completedSetScores(score: String?): List<Pair<Int, Int>> =
        parseSetScores(score).map { it.firstGames to it.secondGames }

    /**
     * Aggregates completed sets and tiebreaks for a player's selected match history.
     * The score is mapped from the recorded match winner's perspective to the requested player.
     *
     * year=null means Career; otherwise only that calendar year is included. Surface and venue
     * filtering is identical to the YTD/Career win-loss and title sections.
     */
    fun setRecord(
        playerId: String,
        meetings: List<H2HMatch>,
        year: Int? = null,
        surface: H2HSurfaceFilter = H2HSurfaceFilter.ALL,
        tournamentIndoorById: Map<String, String> = emptyMap(),
        eligibleTourMatchesOnly: Boolean = false,
    ): H2HSetRecord {
        var setsWon = 0
        var setsLost = 0
        var tiebreaksWon = 0
        var tiebreaksLost = 0
        var setsPlayed = 0
        var tiebreaksPlayed = 0
        var matchesWithScores = 0
        var matchesWithCompletedSets = 0

        meetings.asSequence()
            .filter { year == null || it.year == year }
            .filter { matchesSelectedSurface(it, surface, tournamentIndoorById) }
            .filter { !eligibleTourMatchesOnly || isEligibleTourMatch(it) }
            .filter { it.winnerId == playerId || it.loserId == playerId }
            .forEach { match ->
                // Score coverage is different from completed-set coverage. A retirement such
                // as "5-0 RET" has a recorded partial score but no completed set to count.
                if (match.score.isNullOrBlank()) return@forEach
                matchesWithScores++

                val completedSets = parseSetScores(match.score)
                if (completedSets.isEmpty()) return@forEach
                matchesWithCompletedSets++

                val playerIsWinner = playerId == match.winnerId
                completedSets.forEach { set ->
                    setsPlayed++
                    val playerWonSet = if (playerIsWinner) {
                        set.firstGames > set.secondGames
                    } else {
                        set.secondGames > set.firstGames
                    }
                    if (playerWonSet) setsWon++ else setsLost++

                    if (set.isTiebreak) {
                        tiebreaksPlayed++
                        if (playerWonSet) tiebreaksWon++ else tiebreaksLost++
                    }
                }
            }

        val hasScoreData = setsPlayed > 0
        return H2HSetRecord(
            setsWon = if (hasScoreData) setsWon else null,
            setsLost = if (hasScoreData) setsLost else null,
            winPercentage = if (hasScoreData) setsWon.toDouble() * 100.0 / setsPlayed else null,
            tiebreaksWon = if (hasScoreData) tiebreaksWon else null,
            tiebreaksLost = if (hasScoreData) tiebreaksLost else null,
            tiebreakWinPercentage = if (tiebreaksWon + tiebreaksLost > 0) {
                tiebreaksWon.toDouble() * 100.0 / (tiebreaksWon + tiebreaksLost)
            } else {
                null
            },
            setsPlayed = setsPlayed,
            tiebreaksPlayed = tiebreaksPlayed,
            matchesWithScores = matchesWithScores,
            matchesWithCompletedSets = matchesWithCompletedSets,
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
