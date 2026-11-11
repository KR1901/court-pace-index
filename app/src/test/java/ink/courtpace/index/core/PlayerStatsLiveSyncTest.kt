package ink.courtpace.index.core.playerstats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsLiveSyncTest {

    private val header = listOf(
        "tourney_id", "tourney_name", "surface", "indoor", "tourney_date",
        "tourney_level", "match_num", "winner_id", "winner_name", "winner_ht", "winner_ioc",
        "loser_id", "loser_name", "loser_ht", "loser_ioc", "score",
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
    ): String = listOf(
        tournament, "\"" + tournamentName.replace("\"", "\"\"") + "\"",
        surface, indoor, "20261109", "250", matchNumber,
        winnerId, winnerName, winnerHeight, "USA",
        loserId, loserName, loserHeight, "ESP", score,
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
        assertEquals(2, result.data.meta.playersWithEligibleArchiveResults)

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
