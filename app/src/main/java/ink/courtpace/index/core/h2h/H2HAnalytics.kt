package ink.courtpace.index.core.h2h

import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class H2HSurfaceFilter(val label: String) {
    ALL("All"),
    HARD("Hard"),
    OUTDOOR_HARD("Outdoor Hard"),
    INDOOR_HARD("Indoor Hard"),
    GRASS("Grass"),
    CLAY("Clay"),
}

data class H2HRecord(
    val wins: Int,
    val losses: Int,
    val titles: Int,
    val matches: Int,
)

object H2HAnalytics {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    private val nonTitleEvents = listOf(
        "davis cup",
        "united cup",
        "laver cup",
        "atp cup",
        "olympic",
        "hopman cup",
        "world team cup",
    )

    fun record(
        playerId: String,
        matches: List<H2HMatch>,
        year: Int?,
        surface: H2HSurfaceFilter,
    ): H2HRecord {
        val filtered = matches.filter { match ->
            (year == null || match.year == year) && matchesSurface(match, surface)
        }
        val wins = filtered.count { it.winnerId == playerId }
        val losses = filtered.count { it.loserId == playerId }
        val titles = filtered.asSequence()
            .filter { it.winnerId == playerId && isTitleFinal(it) }
            .map { it.eventId.ifBlank { it.eventName + "|" + (it.year ?: 0) } }
            .distinct()
            .count()
        return H2HRecord(
            wins = wins,
            losses = losses,
            titles = titles,
            matches = wins + losses,
        )
    }

    fun winsAgainst(playerId: String, meetings: List<H2HMatch>): Int =
        meetings.count { it.winnerId == playerId }

    fun matchesSurface(match: H2HMatch, filter: H2HSurfaceFilter): Boolean = when (filter) {
        H2HSurfaceFilter.ALL -> true
        H2HSurfaceFilter.HARD -> match.surface == "hard"
        H2HSurfaceFilter.OUTDOOR_HARD -> match.surface == "hard" && match.indoor == false
        H2HSurfaceFilter.INDOOR_HARD -> match.surface == "hard" && match.indoor == true
        H2HSurfaceFilter.GRASS -> match.surface == "grass"
        H2HSurfaceFilter.CLAY -> match.surface == "clay"
    }

    fun isTitleFinal(match: H2HMatch): Boolean {
        if (match.round?.uppercase(Locale.ROOT) !in setOf("F", "FINAL")) return false
        if (match.level?.uppercase(Locale.ROOT) == "D") return false
        val name = match.eventName.lowercase(Locale.ROOT)
        return nonTitleEvents.none(name::contains)
    }

    fun age(birthDate: LocalDate?, asOf: LocalDate = LocalDate.now()): Int? {
        if (birthDate == null || birthDate.isAfter(asOf)) return null
        return Period.between(birthDate, asOf).years
    }

    fun formatHeight(heightCm: Int?): String {
        if (heightCm == null || heightCm !in 140..220) return "Unavailable"
        val totalInches = (heightCm / 2.54).toInt()
        val feet = totalInches / 12
        val inches = totalInches % 12
        return "${heightCm} cm · ${feet}′${inches}″"
    }

    fun formatDate(date: LocalDate?): String =
        date?.format(dateFormatter) ?: "Date unavailable"
}
