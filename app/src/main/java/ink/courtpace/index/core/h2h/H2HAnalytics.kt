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
    val titles: Int?,
    val matches: Int?,
) {
    val winPercentage: Double?
        get() {
            val totalWins = wins ?: return null
            val totalLosses = losses ?: return null
            val totalMatches = matches ?: (totalWins + totalLosses)
            return if (totalMatches > 0) totalWins * 100.0 / totalMatches else null
        }
}

object H2HAnalytics {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    /**
     * W/L, Win% and title totals all come from the same annual Player Stats buckets.
     * The buckets are built from the ATP source CSV, including its explicit indoor/outdoor flag,
     * so every surface selector applies consistently to all three displayed measures.
     */
    fun record(
        playerStats: PlayerStatsPlayer?,
        year: Int?,
        surface: H2HSurfaceFilter,
    ): H2HRecord {
        if (playerStats == null) {
            return H2HRecord(wins = null, losses = null, titles = null, matches = null)
        }
        val buckets = playerStats.seasons.asSequence()
            .filter { year == null || it.y == year }
            .mapNotNull { it.b[surface.bucketKey] }
            .toList()
        return H2HRecord(
            wins = buckets.sumOf(PlayerStatsBucket::w),
            losses = buckets.sumOf(PlayerStatsBucket::l),
            titles = buckets.sumOf(PlayerStatsBucket::titles),
            matches = buckets.sumOf(PlayerStatsBucket::mp),
        )
    }

    fun winsAgainst(playerId: String, meetings: List<H2HMatch>): Int =
        meetings.count { it.winnerId == playerId }

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
