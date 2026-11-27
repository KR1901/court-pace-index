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
    fun `annual and ongoing feeds deduplicate and classify Davis Cup singles correctly`() {
        val annual = listOf(
            header,
            row("2026", "1", "P1", "Tomas Machac", "P2", "Liam Draxl", "4", "68", "3", "65"),
            // A different tie can reuse match_num 1 on the same tournament date.
            row("2026", "1", "P17", "Another Tie Winner", "P18", "Another Tie Opponent", "2", "70", "2", "70"),
            row("2026", "2", "P3", "Jiri Lehecka", "P4", "Felix Auger-Aliassime", "", "", "", ""),
            row("2026", "3", "P5", "Player A / Player B", "P6", "Player C / Player D", "4", "40", "3", "42"),
            // Annual scoreless row is superseded by the completed ongoing snapshot below.
            row("2026", "6", "P11", "Recently Completed", "P12", "Recent Opponent", "", "", "", "")
                .replace("6-4 6-4", ""),
            // This fixture remains unplayed in the latest snapshot.
            row("2026", "7", "P13", "Scheduled Winner", "P14", "Scheduled Opponent", "", "", "", "")
                .replace("6-4 6-4", ""),
            // Complete numeric fields with an impossible ace count are malformed, not missing.
            row("2026", "8", "P15", "Malformed Winner", "P16", "Malformed Opponent", "-1", "60", "2", "60"),
            // A walkover is neither unplayed nor a played match missing statistics.
            row("2026", "9", "P19", "Walkover Winner", "P20", "Walkover Opponent", "", "", "", "")
                .replace("6-4 6-4", "W/O"),
            row("2025", "1", "P7", "Player E", "P8", "Player F", "5", "50", "4", "48"),
            row("2026", "5", "", "", "", "", "", "", "", ""),
        ).joinToString("\n")
        val ongoing = listOf(
            header,
            row("2026", "1", "P1", "Tomas Machac", "P2", "Liam Draxl", "9", "70", "6", "90"),
            row("2026", "4", "P9", "Player G", "P10", "Player H", "7", "61", "2", "60"),
            row("2026", "6", "P11", "Recently Completed", "P12", "Recent Opponent", "5", "70", "3", "65"),
        ).joinToString("\n")

        val batch = TmlDavisCupClient().parseMatchRows(annual, ongoing, 2026)

        assertEquals(8, batch.identifiedSingles)
        assertEquals(6, batch.discoveredMatches)
        assertEquals(4, batch.matchesWithServeStats)
        assertEquals(1, batch.missingStatsMatches)
        assertEquals(1, batch.malformedStatsMatches)
        assertEquals(1, batch.scorelessRows)
        assertEquals(1, batch.noPlayRows)
        assertEquals(4, batch.matches.size)
        assertEquals(
            setOf("Tomas Machac", "Another Tie Winner", "Player G", "Recently Completed"),
            batch.matches.map { it.firstPlayer }.toSet(),
        )
        val machac = batch.matches.first { it.firstPlayer == "Tomas Machac" }
        assertEquals("Ongoing feed must replace the annual stats for the same rubber", 9, machac.firstAces)
        assertEquals(70, machac.firstServePoints)
        val recent = batch.matches.first { it.firstPlayer == "Recently Completed" }
        assertEquals(5, recent.firstAces)
        assertEquals(70, recent.firstServePoints)
    }

    @Test
    fun `separates Final 8 rubbers from earlier Davis Cup ties by source tournament ID`() {
        val annual = listOf(
            header,
            row("2026", "1", "P1", "Qualifier Winner", "P2", "Qualifier Opponent", "7", "70", "4", "65",
                tournamentId = "2026-M-DC-WG2"),
            row("2026", "2", "P3", "Group Winner", "P4", "Group Opponent", "", "", "", "",
                tournamentId = "2026-M-DC-GRP"),
            row("2026", "3", "P5", "Final 8 Winner", "P6", "Final 8 Opponent", "9", "80", "5", "75"),
        ).joinToString("\n")
        val ongoing = listOf(
            header,
            row("2026", "3", "P5", "Final 8 Winner", "P6", "Final 8 Opponent", "10", "82", "5", "76"),
            row("2026", "4", "P7", "Second Final 8 Winner", "P8", "Second Final 8 Opponent", "6", "72", "3", "69"),
        ).joinToString("\n")

        val batch = TmlDavisCupClient().parseMatchRows(annual, ongoing, 2026)

        assertEquals(4, batch.identifiedSingles)
        assertEquals(4, batch.discoveredMatches)
        assertEquals(2, batch.restOfDavisCup.identifiedSingles)
        assertEquals(2, batch.restOfDavisCup.discoveredMatches)
        assertEquals(1, batch.restOfDavisCup.matchesWithServeStats)
        assertEquals(1, batch.restOfDavisCup.missingStatsMatches)
        assertEquals(2, batch.final8.identifiedSingles)
        assertEquals(2, batch.final8.discoveredMatches)
        assertEquals(2, batch.final8.matchesWithServeStats)
        assertEquals(0, batch.final8.missingStatsMatches)
        assertEquals(2, batch.final8.matches.size)
        assertEquals(setOf("Final 8 Winner", "Second Final 8 Winner"),
            batch.final8.matches.map { it.firstPlayer }.toSet())
        assertEquals(10, batch.final8.matches.first { it.firstPlayer == "Final 8 Winner" }.firstAces)
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
        tournamentId: String = "2026-M-DC-FLS",
    ): String = listOf(
        tournamentId,
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
