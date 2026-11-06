package ink.courtpace.index.core

import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.AtpMatchStats
import ink.courtpace.index.core.live.AtpStatsParser
import ink.courtpace.index.core.model.LiveSpeedBaseline
import ink.courtpace.index.core.model.LiveSpeedPlayerProfile
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
            .replace(Regex("""(?m)^\s*[A-Z]\.\s+(?:Comesana|Rincon)\s*$"""), "")

        val stats = AtpStatsParser.parse(alternate)!!

        assertEquals(7, stats.firstAces)
        assertEquals(2, stats.secondAces)
        assertEquals(126, stats.firstServePoints)
        assertEquals(132, stats.secondServePoints)
        assertEquals("Player 1", stats.firstPlayer)
        assertEquals("Player 2", stats.secondPlayer)
    }

    @Test
    fun `uses explicit service points when the first serve rows are absent`() {
        val withoutFirstServe = page
            .replace(Regex("""(?m)^.*First serve.*$"""), "")
            .replace(Regex("""(?m)^.*1st serve points won.*$"""), "")
            .replace(Regex("""(?m)^.*2nd serve points won.*$"""), "")

        val stats = AtpStatsParser.parse(withoutFirstServe)!!

        assertEquals(126, stats.firstServePoints)
        assertEquals(132, stats.secondServePoints)
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

    private val baseline = LiveSpeedBaseline(
        season = 2026,
        normalisation = 1.0,
        profiles = listOf(
            LiveSpeedPlayerProfile("A. Server", serveRate = 0.15, concedeRate = 0.10),
            LiveSpeedPlayerProfile("B. Returner", serveRate = 0.25, concedeRate = 0.20),
        ),
    )

    private fun sampleMatches(count: Int = 10) = (1..count).map {
        AtpMatchStats("A. Server", "B. Returner", 6, 40, 6, 40, true)
    }

    @Test
    fun `a rating needs enough matches and adjusted server observations`() {
        assertNull(AceRating.rate(sampleMatches(1), baseline))
        assertNull(
            AceRating.rate(
                sampleMatches(10),
                baseline.copy(profiles = baseline.profiles.filterNot { it.name == "B. Returner" }),
            ),
        )
    }

    @Test
    fun `live result preserves discovered denominator and missing match diagnostics`() {
        val result = AceRating.rate(sampleMatches(), baseline)!!.copy(
            discoveredMatches = 12,
            missingMatchIds = listOf("ms051", "ms054"),
            missingReasons = mapOf(
                "ms051" to "HTTP 404",
                "ms054" to "JSON had no setStats.set0 rows",
            ),
            inProgressMatches = 1,
            walkoversExcluded = 1,
        )

        assertEquals(10, result.matches)
        assertEquals(12, result.discoveredMatches)
        assertEquals(listOf("ms051", "ms054"), result.missingMatchIds)
        assertEquals("HTTP 404", result.missingReasons["ms051"])
        assertEquals(1, result.inProgressMatches)
        assertEquals(1, result.walkoversExcluded)
        assertEquals(20, result.adjustedServerSides)
    }

    @Test
    fun `rating uses server and returner tendencies rather than pooled ace rate`() {
        val ordinary = AceRating.rate(sampleMatches(), baseline)!!
        val strongerReturner = AceRating.rate(
            sampleMatches(),
            baseline.copy(
                profiles = listOf(
                    baseline.profiles[0],
                    baseline.profiles[1].copy(concedeRate = 0.35),
                ),
            ),
        )!!

        assertEquals(0.15, ordinary.aceRate, 1e-9)
        assertTrue(
            "opponent adjustment should change the rating when returner tendency changes",
            kotlin.math.abs(ordinary.rating - strongerReturner.rating) > 1e-3,
        )
    }

    @Test
    fun `historical normalisation centres a tour average event at one`() {
        val raw = AceRating.rate(sampleMatches(), baseline)!!
        val centred = AceRating.rate(sampleMatches(), baseline.copy(normalisation = raw.rating))!!
        assertEquals(1.0, centred.rating, 1e-9)
    }

    @Test
    fun `every parsed match contributes both players service observations`() {
        val matches = sampleMatches(15)
        val result = AceRating.rate(matches, baseline)!!
        assertEquals(15, result.matches)
        assertEquals(30, result.adjustedServerSides)
        assertEquals(12 * 15, result.aces)
        assertEquals(0.15, result.aceRate, 1e-9)
    }
}
