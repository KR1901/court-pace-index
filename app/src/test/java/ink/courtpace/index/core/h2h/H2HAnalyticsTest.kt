package ink.courtpace.index.core.h2h

import ink.courtpace.index.core.playerstats.PlayerSeasonStats
import ink.courtpace.index.core.playerstats.PlayerStatsBucket
import ink.courtpace.index.core.playerstats.PlayerStatsPlayer
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H2HAnalyticsTest {

    private fun match(
        id: String,
        year: Int,
        surface: String,
        round: String = "R16",
        winner: String = "p1",
        eventName: String = "Test Open",
        level: String = "A",
        teamEvent: Boolean? = false,
    ) = H2HMatch(
        id = id,
        eventId = "event-$id",
        eventName = eventName,
        date = LocalDate.of(year, 3, 1),
        year = year,
        surface = surface,
        teamEvent = teamEvent,
        round = round,
        level = level,
        winnerId = winner,
        winnerName = if (winner == "p1") "Player One" else "Player Two",
        loserId = if (winner == "p1") "p2" else "p1",
        loserName = if (winner == "p1") "Player Two" else "Player One",
        score = "6-4 6-4",
    )

    private fun statsPlayer() = PlayerStatsPlayer(
        id = "p1",
        name = "Player One",
        seasons = listOf(
            PlayerSeasonStats(
                y = 2026,
                b = mapOf(
                    "all" to PlayerStatsBucket(mp = 5, w = 3, l = 2),
                    "hardAll" to PlayerStatsBucket(mp = 3, w = 2, l = 1),
                    "hardIndoor" to PlayerStatsBucket(mp = 1, w = 1, l = 0),
                    "hardOutdoor" to PlayerStatsBucket(mp = 1, w = 1, l = 0),
                    "hardUnknown" to PlayerStatsBucket(mp = 1, w = 0, l = 1),
                    "grass" to PlayerStatsBucket(mp = 1, w = 1, l = 0),
                    "clay" to PlayerStatsBucket(mp = 1, w = 0, l = 1),
                ),
            ),
            PlayerSeasonStats(
                y = 2025,
                b = mapOf(
                    "all" to PlayerStatsBucket(mp = 4, w = 2, l = 2),
                    "hardAll" to PlayerStatsBucket(mp = 2, w = 1, l = 1),
                    "hardIndoor" to PlayerStatsBucket(mp = 1, w = 1, l = 0),
                    "hardOutdoor" to PlayerStatsBucket(mp = 1, w = 0, l = 1),
                    "grass" to PlayerStatsBucket(mp = 1, w = 1, l = 0),
                    "clay" to PlayerStatsBucket(mp = 1, w = 0, l = 1),
                ),
            ),
        ),
    )

    @Test
    fun hard_combines_indoor_outdoor_and_unknown_environment_hard_matches() {
        val player = statsPlayer()
        val meetings = listOf(
            match("indoor-final", 2026, "hard", round = "F"),
            match("outdoor", 2026, "hard"),
            match("unknown-hard", 2026, "hard", winner = "p2"),
            match("grass", 2026, "grass"),
            match("clay", 2026, "clay"),
        )

        val hard = H2HAnalytics.record("p1", player, meetings, 2026, H2HSurfaceFilter.HARD)
        val indoor = H2HAnalytics.record("p1", player, meetings, 2026, H2HSurfaceFilter.INDOOR_HARD)
        val outdoor = H2HAnalytics.record("p1", player, meetings, 2026, H2HSurfaceFilter.OUTDOOR_HARD)
        val grass = H2HAnalytics.record("p1", player, meetings, 2026, H2HSurfaceFilter.GRASS)
        val clay = H2HAnalytics.record("p1", player, meetings, 2026, H2HSurfaceFilter.CLAY)
        val all = H2HAnalytics.record("p1", player, meetings, 2026, H2HSurfaceFilter.ALL)

        assertEquals(2, hard.wins)
        assertEquals(1, hard.losses)
        assertEquals(3, hard.matches)
        assertEquals(1, indoor.wins)
        assertEquals(0, indoor.losses)
        assertEquals(1, outdoor.wins)
        assertEquals(0, outdoor.losses)
        assertEquals(1, grass.wins)
        assertEquals(0, clay.wins)
        assertEquals(5, all.matches)
    }

    @Test
    fun ytd_filters_current_calendar_year_while_career_sums_all_seasons() {
        val player = statsPlayer()
        val matches = listOf(
            match("current-final", 2026, "hard", round = "F"),
            match("past-final", 2025, "clay", round = "F", winner = "p2"),
        )

        val ytd = H2HAnalytics.record("p1", player, matches, 2026, H2HSurfaceFilter.ALL)
        val career = H2HAnalytics.record("p1", player, matches, null, H2HSurfaceFilter.ALL)
        val careerClay = H2HAnalytics.record("p1", player, matches, null, H2HSurfaceFilter.CLAY)

        assertEquals(3, ytd.wins)
        assertEquals(2, ytd.losses)
        assertEquals(5, ytd.matches)
        assertEquals(5, career.wins)
        assertEquals(4, career.losses)
        assertEquals(9, career.matches)
        assertEquals(0, careerClay.wins)
        assertEquals(2, careerClay.losses)
    }

    @Test
    fun titles_count_unique_non_team_final_wins_and_do_not_follow_surface_filter() {
        val final = match("final-a", 2026, "hard", round = "F", eventName = "Sydney Open")
        val sameEvent = match("same-event-final", 2026, "hard", round = "F", eventName = "Sydney Open")
            .copy(eventId = final.eventId)
        val matches = listOf(
            final,
            sameEvent,
            match("davis-final", 2026, "hard", round = "F", eventName = "Davis Cup", level = "D"),
            match("team-final", 2026, "hard", round = "F", eventName = "Laver Cup", teamEvent = true),
            match("semifinal", 2026, "clay", round = "SF", eventName = "Rome Open"),
            match("loss-final", 2026, "grass", round = "F", winner = "p2", eventName = "Queens Club"),
        )

        val all = H2HAnalytics.record("p1", statsPlayer(), matches, 2026, H2HSurfaceFilter.ALL)
        val clay = H2HAnalytics.record("p1", statsPlayer(), matches, 2026, H2HSurfaceFilter.CLAY)
        assertEquals(1, all.titles)
        assertEquals(1, clay.titles)
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
    fun missing_player_aggregate_is_not_fabricated_as_zero() {
        val record = H2HAnalytics.record("unknown", null, emptyList(), null, H2HSurfaceFilter.ALL)
        assertNull(record.wins)
        assertNull(record.losses)
        assertNull(record.matches)
        assertEquals(0, record.titles)
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
}
