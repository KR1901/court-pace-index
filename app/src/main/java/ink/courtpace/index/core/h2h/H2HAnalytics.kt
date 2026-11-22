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
    val titles: Int,
    val matches: Int?,
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
     * player-performance API's Match JSON response. Titles are counted independently from
     * match-level final records and intentionally do not change with the W/L surface selector.
     */
    fun record(
        playerId: String,
        playerStats: PlayerStatsPlayer?,
        matches: List<H2HMatch>,
        year: Int?,
        surface: H2HSurfaceFilter,
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
            .map { "${it.year ?: 0}|" + it.eventId.ifBlank { it.eventName } }
            .distinct()
            .count()

        return H2HRecord(
            wins = wins,
            losses = losses,
            titles = titles,
            matches = matchCount,
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
