package ink.courtpace.index.core

import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.AtpMatchStats
import ink.courtpace.index.core.live.AtpStatsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture is the real text of an ATP Stats Centre page (Milan 2026, quarterfinal), which is
 * server-rendered: unlike the tournament results page it carries ace counts.
 */
class AtpStatsParserTest {

    private val page = """
        Francisco Comesana Argentina
        Aspria Tennis Cup trofeo BCS Milan 2026
        Singles Quarterfinal. 03:40:13
        F. Comesana
        4 7 6 5
        D. Rincon
        6 5 7
        Game Set and Match Daniel Rincon. Daniel Rincon wins the match 6-4 5-7 7-6(5).
        Daniel Rincon Spain
        SERVICE STATS
        237
        Serve Rating
        236
        Serve Rating
        7
        Aces
        2
        Aces
        7
        2
        11
        Double Faults
        5
        Double Faults
        82/126 (65%)
        First serve
        64/132 (48%)
        First serve
        56/82 (68%)
        1st serve points won
        46/64 (72%)
        1st serve points won
        15/44 (34%)
        2nd serve points won
        31/68 (46%)
        2nd serve points won
        71/126 (56%)
        Service Points Won
        77/132 (58%)
        Service Points Won
    """.trimIndent()

    @Test
    fun `reads aces and service points for both players`() {
        val stats = AtpStatsParser.parse(page)!!
        assertEquals("F. Comesana", stats.firstPlayer)
        assertEquals("D. Rincon", stats.secondPlayer)
        assertEquals(7, stats.firstAces)
        assertEquals(2, stats.secondAces)
        // 82 first-serve attempts + 44 second-serve attempts = 126 service points.
        assertEquals(126, stats.firstServePoints)
        assertEquals(132, stats.secondServePoints)
    }


    @Test
    fun `parses the alternate ATP layout with labels before values and no player abbreviations`() {
        val alternate = page
            .replace("7\nAces\n2\nAces", "Aces\n7\nAces\n2")
            .replace("82/126 (65%)\nFirst serve", "First serve\n82/126 (65%)")
            .replace("64/132 (48%)\nFirst serve", "First serve\n64/132 (48%)")
            .replace("        F. Comesana\n", "")
            .replace("        D. Rincon\n", "")

        val stats = AtpStatsParser.parse(alternate)!!

        assertEquals(7, stats.firstAces)
        assertEquals(2, stats.secondAces)
        assertEquals(126, stats.firstServePoints)
        assertEquals(132, stats.secondServePoints)
        assertEquals("Player 1", stats.firstPlayer)
        assertEquals("Player 2", stats.secondPlayer)
    }

    @Test
    fun `identifies which side won`() {
        val stats = AtpStatsParser.parse(page)!!
        assertFalse("Rincon won, and he is the second player", stats.firstWon)
    }

    @Test
    fun `the empty template yields nothing`() {
        val template = "PLAYER RESULTS\n{{playerProfile.FirstName}} {{playerProfile.LastName}}"
        assertFalse(AtpStatsParser.hasStats(template))
        assertNull(AtpStatsParser.parse(template))
    }

    @Test
    fun `a rating needs enough matches to mean anything`() {
        val one = AtpStatsParser.parse(page)!!
        assertNull(AceRating.rate(listOf(one)))
    }

    @Test
    fun `a serve-dominated event rates above one`() {
        val matches = (1..AceRating.MIN_MATCHES).map {
            AtpMatchStats("A. Server", "B. Returner", 6, 40, 6, 40, true)
        }
        val result = AceRating.rate(matches)!!
        assertEquals(AceRating.MIN_MATCHES, result.matches)
        assertEquals(12 * AceRating.MIN_MATCHES, result.aces)
        // 12 aces per 80 service points is 15%, well above the 7.83% tour rate.
        assertEquals(0.15 / AceRating.BASELINE_ACE_RATE, result.rating, 1e-6)
        assertTrue("expected a fast reading, got ${result.rating}", result.rating > 1.0)
    }

    @Test
    fun `an event serving at the tour rate rates at one`() {
        // 78 aces per 1000 service points is 7.8%, the tour rate, so the rating is 1.00.
        val matches = (1..AceRating.MIN_MATCHES).map {
            AtpMatchStats("A. Server", "B. Returner", 78, 1000, 78, 1000, true)
        }
        val result = AceRating.rate(matches)!!
        assertEquals(1.0, result.rating, 0.02)
    }

    @Test
    fun `the rating covers every match it is given, not a sample`() {
        // Thirty matches with identical numbers must report thirty, and the same rate.
        val matches = (1..30).map {
            AtpMatchStats("A. Server", "B. Returner", 4, 50, 4, 50, true)
        }
        val result = AceRating.rate(matches)!!
        assertEquals(30, result.matches)
        assertEquals(8 * 30, result.aces)
        // One match must not dominate: dropping half leaves the same rate.
        val half = AceRating.rate(matches.take(15))!!
        assertEquals(result.rating, half.rating, 1e-9)
    }

    @Test
    fun `a slow event rates below one`() {
        val matches = (1..AceRating.MIN_MATCHES).map {
            AtpMatchStats("A. Server", "B. Returner", 1, 60, 1, 60, true)
        }
        val result = AceRating.rate(matches)!!
        assertTrue("expected a slow reading, got ${result.rating}", result.rating < 1.0)
    }
}
