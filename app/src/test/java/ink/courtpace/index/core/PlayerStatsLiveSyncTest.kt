package ink.courtpace.index.core.playerstats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsLiveSyncTest {

    private val header = listOf(
        "tourney_id", "tourney_name", "surface", "indoor", "tourney_date",
        "tourney_level", "match_num", "winner_id", "winner_name", "winner_ht", "winner_ioc",
        "loser_id", "loser_name", "loser_ht", "loser_ioc", "score", "best_of",
        "w_SvGms", "w_bpSaved", "w_bpFaced", "l_SvGms", "l_bpSaved", "l_bpFaced",
    ).joinToString(",")

    private fun row(
        tournament: String,
        tournamentName: String,
        surface: String,
        indoor: String,
        matchNumber: String,
        winnerId: String,
        winnerName: String,
        winnerHeight: String,
        loserId: String,
        loserName: String,
        loserHeight: String,
        score: String,
        winnerServeGames: String,
        winnerBpSaved: String,
        winnerBpFaced: String,
        loserServeGames: String,
        loserBpSaved: String,
        loserBpFaced: String,
        bestOf: String = "3",
        level: String = "250",
    ): String = listOf(
        tournament, "\"" + tournamentName.replace("\"", "\"\"") + "\"",
        surface, indoor, "20261109", level, matchNumber,
        winnerId, winnerName, winnerHeight, "USA",
        loserId, loserName, loserHeight, "ESP", score, bestOf,
        winnerServeGames, winnerBpSaved, winnerBpFaced,
        loserServeGames, loserBpSaved, loserBpFaced,
    ).joinToString(",")

    private fun baseline() = PlayerStatsData(
        meta = PlayerStatsMeta(
            generated = "2026-11-11T00:00:00Z",
            lastSeason = 2026,
        ),
        players = emptyList(),
    )

    @Test
    fun completedMatchesUpdateEveryPlayerAndSurfaceBucket() {
        val csv = listOf(
            header,
            row(
                "2026-429", "Stockholm, ATP", "Hard", "I", "30",
                "M0QI", "Alex Michelsen", "193", "C0C8", "Juan Manuel Cerundolo", "183",
                "6-4 7-6(4)", "9", "1", "2", "8", "3", "4",
            ),
            row(
                "2026-430", "Clay Event", "Clay", "O", "11",
                "M0QI", "Alex Michelsen", "193", "P003", "Third Player", "188",
                "6-4 2-1 RET", "5", "0", "0", "4", "0", "1",
            ),
            row(
                "2026-430", "Clay Event", "Clay", "O", "12",
                "M0QI", "Alex Michelsen", "193", "P004", "Walkover Opponent", "190",
                "W/O", "", "", "", "", "", "",
            ),
        ).joinToString("\n") + "\n"

        val parsed = PlayerStatsLiveCsvParser.parse(csv)
        val result = PlayerStatsLiveAggregator.apply(
            baseline = baseline(),
            rows = parsed,
            season = 2026,
            updatedAt = "2026-11-11T10:00:00Z",
        )

        assertEquals(2, result.addedMatches)
        assertEquals(1, result.matchesWithValidStats)
        assertEquals(3, result.data.players.size)
        assertEquals(3, result.data.meta.players)
        assertEquals(3, result.data.meta.playersWithHeight)
        assertEquals(2, result.data.meta.liveMatchCount)
        assertEquals(2, result.data.meta.currentSeasonMatchKeys.size)

        val alex = result.data.players.single { it.id == "M0QI" }
        val hard = alex.seasons.single { it.y == 2026 }.b.getValue("hardIndoor")
        assertEquals(1, hard.mp)
        assertEquals(1, hard.w)
        assertEquals(9, hard.sp)
        assertEquals(8, hard.sw)
        assertEquals(8, hard.rp)
        assertEquals(1, hard.rw)
        assertEquals(1, hard.sm)

        val all = alex.seasons.single { it.y == 2026 }.b.getValue("all")
        assertEquals(2, all.mp)
        assertEquals(2, all.w)
        assertEquals(9, all.sp) // retirement counts in W-L but partial game stats are excluded
        assertEquals(1, all.sm)
        assertEquals(3, result.data.meta.playersWithEligibleArchiveResults)

        val opponent = result.data.players.single { it.id == "C0C8" }
        val opponentHard = opponent.seasons.single { it.y == 2026 }.b.getValue("hardIndoor")
        assertEquals(8, opponentHard.sp)
        assertEquals(7, opponentHard.sw)
        assertEquals(9, opponentHard.rp)
        assertEquals(1, opponentHard.rw)

        assertFalse(result.data.players.any { it.id == "P004" }) // walkovers are excluded
    }

    @Test
    fun liveAndAnnualCopiesOfSameMatchAreCountedOnlyOnce() {
        val csv = listOf(
            header,
            row(
                "2026-429", "Stockholm", "Hard", "I", "30",
                "M0QI", "Alex Michelsen", "193", "C0C8", "Juan Manuel Cerundolo", "183",
                "6-4 7-6", "9", "1", "2", "8", "3", "4",
            ),
        ).joinToString("\n")

        val first = PlayerStatsLiveAggregator.apply(baseline(), PlayerStatsLiveCsvParser.parse(csv), 2026, "2026-11-11T10:00:00Z")
        val second = PlayerStatsLiveAggregator.apply(first.data, PlayerStatsLiveCsvParser.parse(csv), 2026, "2026-11-11T10:01:00Z")

        assertEquals(1, first.addedMatches)
        assertEquals(0, second.addedMatches)
        assertEquals(1, second.data.meta.liveMatchCount)
        assertEquals(1, second.data.players.single { it.id == "M0QI" }.seasons.single().b.getValue("all").mp)
        assertEquals(first.data, second.data)
    }

    @Test
    fun laterPublishedGameCountersEnrichResultsWithoutDoubleCountingWinLoss() {
        val withoutStats = listOf(
            header,
            row(
                "2026-429", "Stockholm", "Hard", "I", "30",
                "M0QI", "Alex Michelsen", "193", "C0C8", "Juan Manuel Cerundolo", "183",
                "6-4 7-6", "", "", "", "", "", "",
            ),
        ).joinToString("\n")
        val first = PlayerStatsLiveAggregator.apply(
            baseline(), PlayerStatsLiveCsvParser.parse(withoutStats), 2026, "2026-11-11T10:00:00Z",
        )
        assertEquals(1, first.addedMatches)
        assertEquals(0, first.enrichedMatches)
        val firstStats = first.data.players.single { it.id == "M0QI" }.seasons.single().b.getValue("all")
        assertEquals(1, firstStats.mp)
        assertEquals(1, firstStats.w)
        assertEquals(0, firstStats.sp)

        val withStats = listOf(
            header,
            row(
                "2026-429", "Stockholm", "Hard", "I", "30",
                "M0QI", "Alex Michelsen", "193", "C0C8", "Juan Manuel Cerundolo", "183",
                "6-4 7-6", "9", "1", "2", "8", "3", "4",
            ),
        ).joinToString("\n")
        val second = PlayerStatsLiveAggregator.apply(
            first.data, PlayerStatsLiveCsvParser.parse(withStats), 2026, "2026-11-11T10:01:00Z",
        )
        assertEquals(0, second.addedMatches)
        assertEquals(1, second.enrichedMatches)
        assertEquals(1, second.data.meta.liveMatchCount)
        assertEquals(1, second.data.meta.liveEnrichedMatchCount)
        assertEquals(1, second.data.meta.currentSeasonMatchKeys.size)
        assertEquals(1, second.data.meta.currentSeasonStatMatchKeys.size)

        val completed = second.data.players.single { it.id == "M0QI" }.seasons.single().b.getValue("all")
        assertEquals(1, completed.mp)
        assertEquals(1, completed.w)
        assertEquals(9, completed.sp)
        assertEquals(8, completed.sw)
        assertEquals(8, completed.rp)
        assertEquals(1, completed.rw)
        assertEquals(1, completed.sm)
    }

    @Test
    fun inProgressScoresAreIgnoredUntilAPlayerWinsTheMatch() {
        val csv = listOf(
            header,
            row(
                "2026-431", "In Progress Best of Three", "Hard", "O", "40",
                "P001", "Player One", "190", "P002", "Player Two", "185",
                "6-4 3-2", "", "", "", "", "", "",
            ),
            row(
                "2026-432", "In Progress Best of Five", "Clay", "O", "41",
                "P003", "Player Three", "193", "P004", "Player Four", "188",
                "6-4 6-4 2-1", "", "", "", "", "", "",
                bestOf = "5",
            ),
            row(
                "2026-433", "Completed Best of Three", "Hard", "O", "42",
                "P005", "Player Five", "191", "P006", "Player Six", "187",
                "6-4 6-3", "9", "1", "2", "8", "3", "4",
            ),
            row(
                "2026-434", "Completed Best of Five", "Grass", "O", "43",
                "P007", "Player Seven", "194", "P008", "Player Eight", "189",
                "6-4 3-6 6-3 4-6 6-2", "18", "2", "3", "17", "4", "6",
                bestOf = "5",
            ),
        ).joinToString("\n") + "\n"

        val result = PlayerStatsLiveAggregator.apply(
            baseline = baseline(),
            rows = PlayerStatsLiveCsvParser.parse(csv),
            season = 2026,
            updatedAt = "2026-11-11T10:02:00Z",
        )

        assertEquals(2, result.addedMatches)
        assertEquals(4, result.data.players.size)
        assertTrue(result.data.players.none { it.id in setOf("P001", "P002", "P003", "P004") })
        assertEquals(setOf("P005", "P006", "P007", "P008"), result.data.players.map { it.id }.toSet())
        assertEquals(2, result.data.meta.currentSeasonMatchKeys.size)
    }

    @Test
    fun challengerAndQualifyingFeedsUpdateHeightGroupsWithoutChangingTourRankings() {
        val challengerCsv = listOf(
            header,
            row(
                "2026-CH-01", "Challenger Event", "Clay", "O", "51",
                "C001", "Challenger Winner", "190", "C002", "Challenger Loser", "185",
                "6-4 6-3", "9", "1", "2", "8", "2", "3",
                level = "C",
            ),
        ).joinToString("\n")

        val challenger = PlayerStatsLiveAggregator.apply(
            baseline = baseline(),
            rows = PlayerStatsLiveCsvParser.parse(challengerCsv),
            season = 2026,
            updatedAt = "2026-11-12T11:00:00Z",
            sourceKind = "challenger",
        )
        assertEquals(1, challenger.addedMatches)
        assertEquals(1, challenger.matchesWithValidStats)
        assertTrue(challenger.data.players.all { it.seasons.isEmpty() })
        assertEquals(1, challenger.data.players.single { it.id == "C001" }
            .heightGroupSeasons.single { it.y == 2026 }.b.getValue("clay").mp)
        assertEquals(1, challenger.data.meta.heightGroupLiveMatchCount)
        assertEquals(0, challenger.data.meta.liveMatchCount)

        val replay = PlayerStatsLiveAggregator.apply(
            baseline = challenger.data,
            rows = PlayerStatsLiveCsvParser.parse(challengerCsv),
            season = 2026,
            updatedAt = "2026-11-12T11:01:00Z",
            sourceKind = "challenger",
        )
        assertEquals(0, replay.addedMatches)
        assertEquals(challenger.data, replay.data)
    }

    @Test
    fun qualifyingFeedIsAcceptedBySourceAndDoesNotLeakIntoTourStats() {
        val qualifyingCsv = listOf(
            header,
            row(
                "2026-Q-01", "Qualifying Event", "Hard", "I", "52",
                "Q001", "Qualifier Winner", "188", "Q002", "Qualifier Loser", "182",
                "6-3 6-4", "8", "1", "2", "8", "2", "3",
                level = "Q",
            ),
        ).joinToString("\n")

        val result = PlayerStatsLiveAggregator.apply(
            baseline = baseline(),
            rows = PlayerStatsLiveCsvParser.parse(qualifyingCsv),
            season = 2026,
            updatedAt = "2026-11-12T11:02:00Z",
            sourceKind = "qualifying",
        )
        assertEquals(1, result.addedMatches)
        assertTrue(result.data.players.all { it.seasons.isEmpty() })
        assertEquals(1, result.data.players.single { it.id == "Q001" }
            .heightGroupSeasons.single { it.y == 2026 }.b.getValue("hardIndoor").mp)
        assertEquals(0, result.data.meta.liveMatchCount)
        assertEquals(1, result.data.meta.heightGroupLiveMatchCount)
    }

    @Test
    fun parserHandlesCrLfAndRejectsBrokenQuotedRecords() {
        val valid = header + "\r\n" + row(
            "2026-429", "Stockholm, ATP", "Hard", "I", "30",
            "M0QI", "Alex Michelsen", "193", "C0C8", "Juan Manuel Cerundolo", "183",
            "6-4 7-6", "9", "1", "2", "8", "3", "4",
        ) + "\r\n"
        assertEquals(1, PlayerStatsLiveCsvParser.parse(valid).size)
        assertTrue(runCatching { PlayerStatsLiveCsvParser.parse(header + "\n\"broken") }.isFailure)
    }
}
