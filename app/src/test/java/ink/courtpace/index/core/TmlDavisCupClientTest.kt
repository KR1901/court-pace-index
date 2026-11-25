package ink.courtpace.index.core

import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.model.LiveSpeedBaseline
import ink.courtpace.index.data.TmlDavisCupClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TmlDavisCupClientTest {

    private val header = listOf(
        "tourney_id", "tourney_name", "surface", "tourney_level", "indoor",
        "tourney_date", "match_num", "winner_id", "winner_name", "loser_id",
        "loser_name", "score", "w_ace", "w_svpt", "l_ace", "l_svpt",
    ).joinToString(",")

    @Test
    fun `annual and ongoing feeds deduplicate and ignore doubles and other seasons`() {
        val annual = listOf(
            header,
            row("2026", "1", "P1", "Tomas Machac", "P2", "Liam Draxl", "4", "68", "3", "65"),
            row("2026", "2", "P3", "Jiri Lehecka", "P4", "Felix Auger-Aliassime", "", "", "", ""),
            row("2026", "3", "P5", "Player A / Player B", "P6", "Player C / Player D", "4", "40", "3", "42"),
            row("2025", "1", "P7", "Player E", "P8", "Player F", "5", "50", "4", "48"),
        ).joinToString("\n")
        val ongoing = listOf(
            header,
            row("2026", "1", "P1", "Tomas Machac", "P2", "Liam Draxl", "9", "70", "6", "90"),
            row("2026", "4", "P9", "Player G", "P10", "Player H", "7", "61", "2", "60"),
        ).joinToString("\n")

        val batch = TmlDavisCupClient().parseMatchRows(annual, ongoing, 2026)

        assertEquals(3, batch.discoveredMatches)
        assertEquals(2, batch.matchesWithServeStats)
        assertEquals(1, batch.missingStatsMatches)
        assertEquals(2, batch.matches.size)
        assertEquals(setOf("Tomas Machac", "Player G"), batch.matches.map { it.firstPlayer }.toSet())
        val machac = batch.matches.first { it.firstPlayer == "Tomas Machac" }
        assertEquals("Ongoing feed must replace the annual stats for the same rubber", 9, machac.firstAces)
        assertEquals(70, machac.firstServePoints)
    }

    @Test
    fun `Davis Cup target can be calculated while missing-stat rows remain in coverage denominator`() {
        val annual = listOf(
            header,
            row("2026", "1", "P1", "Tomas Machac", "P2", "Liam Draxl", "9", "70", "6", "90"),
            row("2026", "2", "P3", "Jiri Lehecka", "P4", "Felix Auger-Aliassime", "5", "82", "8", "90"),
            row("2026", "3", "P5", "Player E", "P6", "Player F", "", "", "", ""),
        ).joinToString("\n")

        val batch = TmlDavisCupClient().parseMatchRows(annual, header, 2026)
        val baseline = LiveSpeedBaseline(
            season = 2026,
            normalisation = 1.0,
            tourAceRate = 0.10,
        )

        val rating = AceRating.rate(batch.matches, baseline, batch.discoveredMatches)

        assertNotNull(rating)
        assertEquals(2, rating!!.matches)
        assertEquals(3, rating.discoveredMatches)
        assertEquals(6, rating.totalServerSides)
        assertEquals(4, rating.adjustedServerSides)
        assertEquals(4, rating.serverProfilesFallback)
        assertEquals(4, rating.returnerProfilesFallback)
    }

    private fun row(
        year: String,
        matchNumber: String,
        winnerId: String,
        winner: String,
        loserId: String,
        loser: String,
        winnerAces: String,
        winnerServicePoints: String,
        loserAces: String,
        loserServicePoints: String,
    ): String = listOf(
        "2026-M-DC-FLS",
        "Davis Cup Final 8 QF: TST vs TST",
        "Hard",
        "D",
        "I",
        "${year}1124",
        matchNumber,
        winnerId,
        winner,
        loserId,
        loser,
        "6-4 6-4",
        winnerAces,
        winnerServicePoints,
        loserAces,
        loserServicePoints,
    ).joinToString(",")
}
