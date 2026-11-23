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
    fun titles_follow_each_surface_filter_and_unique_final_wins() {
        val indoorFinal = match("indoor-final", 2026, "hard", round = "F", eventName = "Indoor Open")
            .copy(eventId = "2026-001")
        val sameIndoorEvent = match(
            "same-indoor-event-final", 2026, "hard", round = "F", eventName = "Indoor Open",
        ).copy(eventId = indoorFinal.eventId)
        val outdoorFinal = match("outdoor-final", 2026, "hard", round = "F", eventName = "Outdoor Open")
            .copy(eventId = "2026-002")
        val unknownHardFinal = match("unknown-hard-final", 2026, "hard", round = "F", eventName = "Unknown Venue Open")
            .copy(eventId = "2026-003")
        val grassFinal = match("grass-final", 2026, "grass", round = "F", eventName = "Queens Club")
        val clayFinal = match("clay-final", 2026, "clay", round = "F", eventName = "Rome Open")
        val nextSeasonSameEvent = match(
            "next-season-final", 2025, "hard", round = "F", eventName = "Indoor Open",
        ).copy(eventId = indoorFinal.eventId)
        val matches = listOf(
            indoorFinal,
            sameIndoorEvent,
            outdoorFinal,
            unknownHardFinal,
            grassFinal,
            clayFinal,
            nextSeasonSameEvent,
            match("davis-final", 2026, "hard", round = "F", eventName = "Davis Cup", level = "D"),
            match("challenger-final", 2026, "clay", round = "F", eventName = "Challenger Open", level = "C"),
            match("team-final", 2026, "hard", round = "F", eventName = "Laver Cup", teamEvent = true),
            match("semifinal", 2026, "clay", round = "SF", eventName = "Rome Open"),
            match("loss-final", 2026, "grass", round = "F", winner = "p2", eventName = "Queens Club"),
        )
        val venues = mapOf(
            "2026-001" to "indoor",
            "2026-002" to "outdoor",
            "2025-001" to "outdoor",
        )

        fun record(surface: H2HSurfaceFilter, year: Int? = 2026) =
            H2HAnalytics.record("p1", statsPlayer(), matches, year, surface, venues)

        assertEquals(5, record(H2HSurfaceFilter.ALL).titles)
        assertEquals(3, record(H2HSurfaceFilter.HARD).titles)
        assertEquals(1, record(H2HSurfaceFilter.INDOOR_HARD).titles)
        assertEquals(1, record(H2HSurfaceFilter.OUTDOOR_HARD).titles)
        assertEquals(1, record(H2HSurfaceFilter.GRASS).titles)
        assertEquals(1, record(H2HSurfaceFilter.CLAY).titles)
        assertEquals(6, record(H2HSurfaceFilter.ALL, year = null).titles)
        assertEquals(2, H2HAnalytics.record(
            "p1", statsPlayer(), matches, null, H2HSurfaceFilter.INDOOR_HARD, venues,
        ).titles)
    }

    @Test
    fun win_percentage_uses_wins_over_wins_plus_losses() {
        val ytd = H2HAnalytics.record("p1", statsPlayer(), emptyList(), 2026, H2HSurfaceFilter.ALL)
        assertEquals(60.0, ytd.winPercentage!!, 0.0001)

        val zeroRecord = PlayerStatsPlayer(
            id = "p1",
            name = "Player One",
            seasons = listOf(PlayerSeasonStats(2026, mapOf("all" to PlayerStatsBucket()))),
        )
        val noMatches = H2HAnalytics.record("p1", zeroRecord, emptyList(), 2026, H2HSurfaceFilter.ALL)
        assertNull(noMatches.winPercentage)
    }

    @Test
    fun set_scores_count_tiebreak_sets_and_map_winner_first_scores_to_each_player() {
        val firstMatch = match("set-a", 2026, "hard").copy(
            score = "6-4 7-6(5) 4-6 7-6(2)",
        )
        val secondMatch = match("set-b", 2025, "clay", winner = "p2").copy(
            score = "6-3 3-6 6-4",
        )
        val meetings = listOf(firstMatch, secondMatch)

        val first = H2HAnalytics.setRecord("p1", meetings)
        val second = H2HAnalytics.setRecord("p2", meetings)

        assertEquals(4, first.setsWon)
        assertEquals(3, first.setsLost)
        assertEquals(57.142857, first.winPercentage!!, 0.0001)
        assertEquals(2, first.tiebreaksWon)
        assertEquals(0, first.tiebreaksLost)
        assertEquals(100.0, first.tiebreakWinPercentage!!, 0.0001)
        assertEquals(7, first.setsPlayed)
        assertEquals(2, first.matchesWithScores)

        assertEquals(3, second.setsWon)
        assertEquals(4, second.setsLost)
        assertEquals(42.857142, second.winPercentage!!, 0.0001)
        assertEquals(0, second.tiebreaksWon)
        assertEquals(2, second.tiebreaksLost)
        assertEquals(0.0, second.tiebreakWinPercentage!!, 0.0001)
        assertEquals(first.setsPlayed, second.setsPlayed)
    }

    @Test
    fun retirement_counts_completed_sets_but_ignores_the_unfinished_set() {
        val retired = match("retired", 2026, "hard").copy(
            score = "6-4 3-2 RET.",
        )
        val record = H2HAnalytics.setRecord("p1", listOf(retired))
        val opponent = H2HAnalytics.setRecord("p2", listOf(retired))

        assertEquals(1, record.setsWon)
        assertEquals(0, record.setsLost)
        assertEquals(100.0, record.winPercentage!!, 0.0001)
        assertEquals(1, record.setsPlayed)
        assertEquals(0, record.tiebreaksWon)
        assertEquals(0, record.tiebreaksLost)
        assertNull(record.tiebreakWinPercentage)
        assertEquals(1, record.matchesWithScores)
        assertEquals(1, record.matchesWithCompletedSets)
        assertEquals(0, opponent.setsWon)
        assertEquals(1, opponent.setsLost)
        assertEquals(0.0, opponent.winPercentage!!, 0.0001)
    }

    @Test
    fun deciding_match_tiebreak_counts_as_a_completed_set() {
        val match = match("match-tiebreak", 2026, "hard").copy(
            score = "6-4 4-6 [10-8]",
        )
        val winner = H2HAnalytics.setRecord("p1", listOf(match))
        val loser = H2HAnalytics.setRecord("p2", listOf(match))

        assertEquals(2, winner.setsWon)
        assertEquals(1, winner.setsLost)
        assertEquals(1, winner.tiebreaksWon)
        assertEquals(0, winner.tiebreaksLost)
        assertEquals(1, loser.setsWon)
        assertEquals(2, loser.setsLost)
        assertEquals(0, loser.tiebreaksWon)
        assertEquals(1, loser.tiebreaksLost)
        assertEquals(0.0, loser.tiebreakWinPercentage!!, 0.0001)
    }

    @Test
    fun incomplete_sets_and_missing_scores_do_not_inflate_set_totals() {
        val partial = match("partial", 2026, "hard").copy(score = "6-4 5-5")
        val missing = match("missing", 2026, "clay").copy(score = null)
        val record = H2HAnalytics.setRecord("p1", listOf(partial, missing))

        assertEquals(1, record.setsWon)
        assertEquals(0, record.setsLost)
        assertEquals(1, record.setsPlayed)
        assertEquals(1, record.matchesWithScores)
        assertEquals(1, record.matchesWithCompletedSets)
        assertEquals(100.0, record.winPercentage!!, 0.0001)
    }

    @Test
    fun no_completed_set_scores_render_as_unavailable_instead_of_zero_record() {
        val record = H2HAnalytics.setRecord(
            "p1",
            listOf(match("unfinished", 2026, "hard").copy(score = "2-1 RET.")),
        )

        assertNull(record.setsWon)
        assertNull(record.setsLost)
        assertNull(record.winPercentage)
        assertNull(record.tiebreaksWon)
        assertNull(record.tiebreaksLost)
        assertEquals(0, record.setsPlayed)
        assertEquals(1, record.matchesWithScores)
        assertEquals(0, record.matchesWithCompletedSets)
    }

    @Test
    fun player_set_and_tiebreak_records_follow_ytd_career_and_all_surface_filters() {
        val matches = listOf(
            match("ih", 2026, "hard").copy(
                eventId = "2026-001",
                score = "7-6(4) 6-4",
            ),
            match("oh", 2026, "hard").copy(
                eventId = "2026-002",
                score = "6-7(5) 7-6(3) 6-4",
            ),
            match("unknown-hard", 2026, "hard", winner = "p2").copy(
                eventId = "2026-003",
                score = "6-4 6-7(4) 6-4",
            ),
            match("grass", 2026, "grass").copy(score = "6-4 6-4"),
            match("clay", 2026, "clay", winner = "p2").copy(score = "7-6(2) 6-4"),
            match("challenger", 2026, "hard", level = "C").copy(
                score = "6-0 6-0",
            ),
            match("qualifying", 2026, "hard", round = "Q1", level = "A").copy(
                score = "7-6(4) 6-4",
            ),
            match("prior-hard", 2025, "hard").copy(
                eventId = "2025-001",
                score = "7-6(6) 7-6(3)",
            ),
        )
        val venues = mapOf(
            "2026-001" to "indoor",
            "2026-002" to "outdoor",
            "2025-001" to "outdoor",
        )

        fun record(surface: H2HSurfaceFilter, year: Int? = 2026) =
            H2HAnalytics.setRecord(
                "p1", matches, year, surface, venues, eligibleTourMatchesOnly = true,
            )

        val all = record(H2HSurfaceFilter.ALL)
        assertEquals(7, all.setsWon)
        assertEquals(5, all.setsLost)
        assertEquals(3, all.tiebreaksWon)
        assertEquals(2, all.tiebreaksLost)
        assertEquals(60.0, all.tiebreakWinPercentage!!, 0.001)
        assertEquals(58.3333, all.winPercentage!!, 0.001)

        val hard = record(H2HSurfaceFilter.HARD)
        assertEquals(5, hard.setsWon)
        assertEquals(3, hard.setsLost)
        assertEquals(3, hard.tiebreaksWon)
        assertEquals(1, hard.tiebreaksLost)
        assertEquals(75.0, hard.tiebreakWinPercentage!!, 0.001)

        val indoor = record(H2HSurfaceFilter.INDOOR_HARD)
        assertEquals(2, indoor.setsWon)
        assertEquals(0, indoor.setsLost)
        assertEquals(1, indoor.tiebreaksWon)
        assertEquals(0, indoor.tiebreaksLost)
        assertEquals(100.0, indoor.tiebreakWinPercentage!!, 0.001)

        val outdoor = record(H2HSurfaceFilter.OUTDOOR_HARD)
        assertEquals(2, outdoor.setsWon)
        assertEquals(1, outdoor.setsLost)
        assertEquals(1, outdoor.tiebreaksWon)
        assertEquals(1, outdoor.tiebreaksLost)
        assertEquals(50.0, outdoor.tiebreakWinPercentage!!, 0.001)

        val grass = record(H2HSurfaceFilter.GRASS)
        assertEquals(2, grass.setsWon)
        assertEquals(0, grass.setsLost)
        assertEquals(0, grass.tiebreaksWon)
        assertEquals(0, grass.tiebreaksLost)
        assertNull(grass.tiebreakWinPercentage)

        val clay = record(H2HSurfaceFilter.CLAY)
        assertEquals(0, clay.setsWon)
        assertEquals(2, clay.setsLost)
        assertEquals(0, clay.tiebreaksWon)
        assertEquals(1, clay.tiebreaksLost)
        assertEquals(0.0, clay.tiebreakWinPercentage!!, 0.001)

        val careerAll = record(H2HSurfaceFilter.ALL, year = null)
        assertEquals(9, careerAll.setsWon)
        assertEquals(5, careerAll.setsLost)
        assertEquals(5, careerAll.tiebreaksWon)
        assertEquals(2, careerAll.tiebreaksLost)
        assertEquals(71.428571, careerAll.tiebreakWinPercentage!!, 0.001)

        val careerHard = record(H2HSurfaceFilter.HARD, year = null)
        assertEquals(7, careerHard.setsWon)
        assertEquals(3, careerHard.setsLost)
        assertEquals(5, careerHard.tiebreaksWon)
        assertEquals(1, careerHard.tiebreaksLost)
        assertEquals(83.333333, careerHard.tiebreakWinPercentage!!, 0.001)

        val careerIndoor = record(H2HSurfaceFilter.INDOOR_HARD, year = null)
        assertEquals(2, careerIndoor.setsWon)
        assertEquals(0, careerIndoor.setsLost)
        assertEquals(1, careerIndoor.tiebreaksWon)
        assertEquals(0, careerIndoor.tiebreaksLost)
        assertEquals(100.0, careerIndoor.tiebreakWinPercentage!!, 0.001)

        val careerOutdoor = record(H2HSurfaceFilter.OUTDOOR_HARD, year = null)
        assertEquals(4, careerOutdoor.setsWon)
        assertEquals(1, careerOutdoor.setsLost)
        assertEquals(3, careerOutdoor.tiebreaksWon)
        assertEquals(1, careerOutdoor.tiebreaksLost)
        assertEquals(75.0, careerOutdoor.tiebreakWinPercentage!!, 0.001)

        val careerGrass = record(H2HSurfaceFilter.GRASS, year = null)
        assertEquals(2, careerGrass.setsWon)
        assertEquals(0, careerGrass.setsLost)
        assertEquals(0, careerGrass.tiebreaksWon)
        assertEquals(0, careerGrass.tiebreaksLost)
        assertNull(careerGrass.tiebreakWinPercentage)

        val careerClay = record(H2HSurfaceFilter.CLAY, year = null)
        assertEquals(0, careerClay.setsWon)
        assertEquals(2, careerClay.setsLost)
        assertEquals(0, careerClay.tiebreaksWon)
        assertEquals(1, careerClay.tiebreaksLost)
        assertEquals(0.0, careerClay.tiebreakWinPercentage!!, 0.001)
    }

    @Test
    fun missing_pair_score_is_backfilled_from_exact_player_history_match() {
        val pairMatch = match("same-match", 2026, "hard").copy(score = null)
        val playerHistoryMatch = match("same-match", 2026, "hard").copy(score = "7-6(5) 6-4")

        val merged = H2HRepository.mergeMissingScores(
            meetings = listOf(pairMatch),
            playerHistory = listOf(playerHistoryMatch),
        )

        assertEquals("7-6(5) 6-4", merged.single().score)
    }

    @Test
    fun score_fallback_does_not_overwrite_existing_partial_retirement_score() {
        val pairMatch = match("retired-match", 2025, "hard").copy(score = "5-0 RET.")
        val playerHistoryMatch = match("retired-match", 2025, "hard").copy(score = "6-4 6-4")

        val merged = H2HRepository.mergeMissingScores(
            meetings = listOf(pairMatch),
            playerHistory = listOf(playerHistoryMatch),
        )

        assertEquals("5-0 RET.", merged.single().score)
    }

    @Test
    fun conflicting_player_history_scores_are_not_guessed() {
        val pairMatch = match("conflicting-match", 2026, "hard").copy(score = null)
        val firstHistory = match("conflicting-match", 2026, "hard").copy(score = "6-4 6-4")
        val secondHistory = match("conflicting-match", 2026, "hard").copy(score = "7-6(5) 6-4")

        val merged = H2HRepository.mergeMissingScores(
            meetings = listOf(pairMatch),
            playerHistory = listOf(firstHistory, secondHistory),
        )

        assertNull(merged.single().score)
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
