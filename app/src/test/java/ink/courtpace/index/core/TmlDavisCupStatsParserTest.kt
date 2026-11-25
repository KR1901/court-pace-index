package ink.courtpace.index.core

import ink.courtpace.index.core.live.TmlDavisCupStatsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmlDavisCupStatsParserTest {

    private val header = "tourney_id,tourney_name,tourney_level,tourney_date,match_num,best_of,score,winner_id,winner_name,loser_id,loser_name,w_ace,w_svpt,l_ace,l_svpt"

    private fun row(
        id: String,
        match: String,
        score: String,
        winner: String,
        loser: String,
        winnerAces: String,
        winnerPoints: String,
        loserAces: String,
        loserPoints: String,
        date: String = "20261124",
        level: String = "D",
        name: String = "Davis Cup Finals QF: CZE vs CAN",
    ) = listOf(
        id, name, level, date, match, "3", score, "winner-$match", winner,
        "loser-$match", loser, winnerAces, winnerPoints, loserAces, loserPoints,
    ).joinToString(",")

    @Test
    fun `parses completed Davis Cup singles with both service point denominators`() {
        val csv = listOf(
            header,
            row(
                id = "2026-final8", match = "1", score = "6-2 3-6 6-2",
                winner = "Tomas Machac", loser = "Liam Draxl",
                winnerAces = "9", winnerPoints = "70", loserAces = "6", loserPoints = "90",
            ),
        ).joinToString("\n")

        val matches = TmlDavisCupStatsParser.parseSeason(listOf(csv), 2026)

        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals("Tomas Machac", match.firstPlayer)
        assertEquals("Liam Draxl", match.secondPlayer)
        assertEquals(9, match.firstAces)
        assertEquals(70, match.firstServePoints)
        assertEquals(6, match.secondAces)
        assertEquals(90, match.secondServePoints)
        assertTrue(match.firstWon)
        assertEquals("2026-final8:1", match.matchId)
    }

    @Test
    fun `ongoing snapshot replaces annual duplicate and new completed rows are added once`() {
        val annual = listOf(
            header,
            row(
                id = "2026-final8", match = "1", score = "6-2 3-6 6-2",
                winner = "Tomas Machac", loser = "Liam Draxl",
                winnerAces = "8", winnerPoints = "70", loserAces = "6", loserPoints = "90",
            ),
        ).joinToString("\n")
        val ongoing = listOf(
            header,
            row(
                id = "2026-final8", match = "1", score = "6-2 3-6 6-2",
                winner = "Tomas Machac", loser = "Liam Draxl",
                winnerAces = "9", winnerPoints = "70", loserAces = "6", loserPoints = "90",
            ),
            row(
                id = "2026-final8", match = "2", score = "7-6(4) 6-2",
                winner = "Jiri Lehecka", loser = "Felix Auger-Aliassime",
                winnerAces = "5", winnerPoints = "82", loserAces = "8", loserPoints = "90",
            ),
        ).joinToString("\n")

        val matches = TmlDavisCupStatsParser.parseSeason(listOf(annual, ongoing), 2026)

        assertEquals(2, matches.size)
        assertEquals(9, matches.first().firstAces)
        assertEquals(2, matches.mapNotNull { it.matchId }.distinct().size)
    }

    @Test
    fun `rejects partial matches walkovers other years and non Davis Cup rows`() {
        val csv = listOf(
            header,
            row(
                id = "live", match = "1", score = "6-2 3-6 3-4",
                winner = "Tomas Machac", loser = "Liam Draxl",
                winnerAces = "5", winnerPoints = "50", loserAces = "4", loserPoints = "55",
            ),
            row(
                id = "walkover", match = "2", score = "W/O",
                winner = "Jiri Lehecka", loser = "Felix Auger-Aliassime",
                winnerAces = "0", winnerPoints = "0", loserAces = "0", loserPoints = "0",
            ),
            row(
                id = "old", match = "3", score = "6-2 6-2",
                winner = "Alex Player", loser = "Other Player",
                winnerAces = "4", winnerPoints = "50", loserAces = "3", loserPoints = "55",
                date = "20250920",
            ),
            row(
                id = "atp", match = "4", score = "6-2 6-2",
                winner = "Alex Player", loser = "Other Player",
                winnerAces = "4", winnerPoints = "50", loserAces = "3", loserPoints = "55",
                level = "250", name = "Montpellier",
            ),
            row(
                id = "invalid", match = "5", score = "6-2 6-2",
                winner = "Alex Player", loser = "Other Player",
                winnerAces = "90", winnerPoints = "50", loserAces = "3", loserPoints = "55",
            ),
        ).joinToString("\n")

        assertTrue(TmlDavisCupStatsParser.parseSeason(listOf(csv), 2026).isEmpty())
    }

    @Test
    fun `reads quoted commas and escaped quotes from CSV names`() {
        val csv = listOf(
            header,
            "${row(
                id = "2026-final8", match = "1", score = "6-2 6-2",
                winner = "Player One", loser = "Player Two",
                winnerAces = "4", winnerPoints = "50", loserAces = "3", loserPoints = "55",
            ).replace("Player One", "\"Player, One\"")}",
        ).joinToString("\n")

        val matches = TmlDavisCupStatsParser.parseSeason(listOf(csv), 2026)

        assertEquals("Player, One", matches.single().firstPlayer)
    }
}
