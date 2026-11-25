package ink.courtpace.index.core

import ink.courtpace.index.core.live.DavisCupStatsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DavisCupStatsParserTest {

    @Test
    fun parsesCompletedFinalEightSinglesAcesAndTotalServicePoints() {
        val page = """
            # Tomas Machac vs Liam Draxl
            Quarterfinal · 24 Nov 2026 · Hard · 1h 56
            Statistics
            Match
            Set 1
            Set 2
            Set 3
            Machac Draxl
            Key stats
            Aces
            9 6
            First serves in
            67%82%
            First-serve points won
            77%36/47 64%47/74
            Service points won
            70%49/70 57%51/90
            Total points
            88 72
        """.trimIndent()

        val parsed = DavisCupStatsParser.parse(page)!!

        assertEquals("Tomas Machac", parsed.firstPlayer)
        assertEquals("Liam Draxl", parsed.secondPlayer)
        assertEquals(9, parsed.firstAces)
        assertEquals(70, parsed.firstServePoints)
        assertEquals(6, parsed.secondAces)
        assertEquals(90, parsed.secondServePoints)
    }

    @Test
    fun rejectsLivePagesEvenWhenPartialStatisticsArePresent() {
        val page = """
            # Jannik Sinner vs Carlos Alcaraz
            Live
            Statistics
            Aces
            4 3
            Service points won
            70%20/30 60%18/30
        """.trimIndent()

        assertNull(DavisCupStatsParser.parse(page))
    }

    @Test
    fun rejectsCompletedPagesWithoutTotalServicePointDenominators() {
        val page = """
            # Player One vs Player Two
            Quarterfinal · 24 Nov 2026 · Hard · 1h 12
            Statistics
            Aces
            5 4
            First serves in
            65%42/65 61%35/57
        """.trimIndent()

        assertNull(DavisCupStatsParser.parse(page))
    }
}
