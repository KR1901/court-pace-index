package ink.courtpace.index.core.h2h

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H2HAnalyticsTest {

    private fun match(
        id: String,
        year: Int,
        surface: String,
        indoor: Boolean? = null,
        round: String = "R16",
        winner: String = "p1",
        eventName: String = "Test Open",
        level: String = "A",
    ) = H2HMatch(
        id = id,
        eventId = "event-$id",
        eventName = eventName,
        date = LocalDate.of(year, 3, 1),
        year = year,
        surface = surface,
        indoor = indoor,
        round = round,
        level = level,
        winnerId = winner,
        winnerName = if (winner == "p1") "Player One" else "Player Two",
        loserId = if (winner == "p1") "p2" else "p1",
        loserName = if (winner == "p1") "Player Two" else "Player One",
        score = "6-4 6-4",
    )

    @Test
    fun hard_combines_indoor_outdoor_and_unknown_environment() {
        val matches = listOf(
            match("indoor", 2026, "hard", indoor = true),
            match("outdoor", 2026, "hard", indoor = false),
            match("unknown", 2026, "hard", indoor = null),
            match("grass", 2026, "grass"),
            match("clay", 2026, "clay"),
        )

        assertEquals(3, H2HAnalytics.record("p1", matches, 2026, H2HSurfaceFilter.HARD).matches)
        assertEquals(1, H2HAnalytics.record("p1", matches, 2026, H2HSurfaceFilter.INDOOR_HARD).matches)
        assertEquals(1, H2HAnalytics.record("p1", matches, 2026, H2HSurfaceFilter.OUTDOOR_HARD).matches)
        assertEquals(1, H2HAnalytics.record("p1", matches, 2026, H2HSurfaceFilter.GRASS).matches)
        assertEquals(1, H2HAnalytics.record("p1", matches, 2026, H2HSurfaceFilter.CLAY).matches)
        assertEquals(5, H2HAnalytics.record("p1", matches, 2026, H2HSurfaceFilter.ALL).matches)
    }

    @Test
    fun ytd_selection_filters_by_calendar_year_without_changing_career_totals() {
        val matches = listOf(
            match("current-hard", 2026, "hard", indoor = false),
            match("old-clay", 2025, "clay"),
        )

        assertEquals(1, H2HAnalytics.record("p1", matches, 2026, H2HSurfaceFilter.ALL).matches)
        assertEquals(2, H2HAnalytics.record("p1", matches, null, H2HSurfaceFilter.ALL).matches)
        assertEquals(1, H2HAnalytics.record("p1", matches, null, H2HSurfaceFilter.CLAY).matches)
    }

    @Test
    fun titles_count_unique_atp_final_wins_but_not_team_competitions() {
        val matches = listOf(
            match("final-a", 2026, "hard", round = "F", eventName = "Sydney Open"),
            match("same-event-final", 2026, "hard", round = "FINAL", eventName = "Sydney Open"),
            match("davis-final", 2026, "hard", round = "F", eventName = "Davis Cup"),
            match("team-final", 2026, "hard", round = "F", eventName = "Laver Cup"),
            match("semifinal", 2026, "clay", round = "SF", eventName = "Rome Open"),
            match("loss-final", 2026, "grass", round = "F", winner = "p2", eventName = "Queens Club"),
        )
        val sameEvent = matches[1].copy(eventId = matches[0].eventId)
        val rows = matches.toMutableList().also { it[1] = sameEvent }

        assertEquals(1, H2HAnalytics.record("p1", rows, null, H2HSurfaceFilter.ALL).titles)
    }

    @Test
    fun head_to_head_win_count_follows_winner_id() {
        val meetings = listOf(
            match("a", 2026, "hard", winner = "p1"),
            match("b", 2025, "clay", winner = "p2"),
            match("c", 2024, "grass", winner = "p1"),
        )
        assertEquals(2, H2HAnalytics.winsAgainst("p1", meetings))
        assertEquals(1, H2HAnalytics.winsAgainst("p2", meetings))
    }

    @Test
    fun height_is_shown_in_metric_and_imperial_units() {
        assertEquals("198 cm · 6′6″", H2HAnalytics.formatHeight(198))
        assertEquals("Unavailable", H2HAnalytics.formatHeight(null))
        assertEquals("Unavailable", H2HAnalytics.formatHeight(250))
    }

    @Test
    fun meeting_dates_use_dd_mm_yyyy() {
        assertEquals("07/02/2026", H2HAnalytics.formatDate(LocalDate.of(2026, 2, 7)))
        assertEquals("Date unavailable", H2HAnalytics.formatDate(null))
    }

    @Test
    fun invalid_future_birth_date_is_not_converted_into_a_negative_age() {
        assertEquals(null, H2HAnalytics.age(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 11, 22)))
        assertEquals(30, H2HAnalytics.age(LocalDate.of(1996, 11, 22), LocalDate.of(2026, 11, 22)))
    }

    @Test
    fun surface_matching_is_explicit_for_hard_subcategories() {
        assertTrue(H2HAnalytics.matchesSurface(match("i", 2026, "hard", true), H2HSurfaceFilter.INDOOR_HARD))
        assertTrue(H2HAnalytics.matchesSurface(match("o", 2026, "hard", false), H2HSurfaceFilter.OUTDOOR_HARD))
        assertFalse(H2HAnalytics.matchesSurface(match("u", 2026, "hard", null), H2HSurfaceFilter.INDOOR_HARD))
        assertFalse(H2HAnalytics.matchesSurface(match("g", 2026, "grass"), H2HSurfaceFilter.HARD))
    }
}
