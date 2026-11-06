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
    fun `ATP abbreviated player names resolve to full historical profile names`() {
        val realisticBaseline = LiveSpeedBaseline(
            season = 2026,
            normalisation = 1.0,
            profiles = listOf(
                LiveSpeedPlayerProfile("Alex de Minaur", serveRate = 0.12, concedeRate = 0.10),
                LiveSpeedPlayerProfile("Andrey Rublev", serveRate = 0.14, concedeRate = 0.08),
            ),
        )
        val matches = (1..10).map {
            AtpMatchStats("A. DE MINAUR", "A. RUBLEV", 6, 80, 5, 82, true)
        }

        val coverage = AceRating.coverage(matches, realisticBaseline)
        val result = AceRating.rate(matches, realisticBaseline)!!

        assertEquals(20, coverage.totalServerSides)
        assertEquals(20, coverage.serverProfilesMatched)
        assertEquals(20, coverage.returnerProfilesMatched)
        assertEquals(20, coverage.adjustedServerSides)
        assertEquals(20, result.adjustedServerSides)
    }

    @Test
    fun `ATP names with multiple initials resolve compound given names`() {
        val realisticBaseline = LiveSpeedBaseline(
            season = 2026,
            normalisation = 1.0,
            profiles = listOf(
                LiveSpeedPlayerProfile("Juan Manuel Cerundolo", serveRate = 0.11, concedeRate = 0.09),
                LiveSpeedPlayerProfile("Alex de Minaur", serveRate = 0.12, concedeRate = 0.10),
            ),
        )
        val matches = (1..10).map {
            AtpMatchStats("J. M. CERUNDOLO", "A. DE MINAUR", 4, 80, 6, 82, true)
        }

        val result = AceRating.rate(matches, realisticBaseline)!!

        assertEquals(20, result.adjustedServerSides)
    }

    @Test
    fun `ATP can omit a middle initial from a compound given name`() {
        val realisticBaseline = LiveSpeedBaseline(
            season = 2026,
            normalisation = 1.0,
            profiles = listOf(
                LiveSpeedPlayerProfile("Juan Manuel Cerundolo", serveRate = 0.11, concedeRate = 0.09),
                LiveSpeedPlayerProfile("Alex de Minaur", serveRate = 0.12, concedeRate = 0.10),
            ),
        )
        val matches = (1..10).map {
            AtpMatchStats("J. CERUNDOLO", "A. DE MINAUR", 4, 80, 6, 82, true)
        }

        val result = AceRating.rate(matches, realisticBaseline)!!

        assertEquals(20, result.adjustedServerSides)
    }

    @Test
    fun `ATP shortens compound names to a middle initial and partial surname`() {
        val realisticBaseline = LiveSpeedBaseline(
            season = 2026,
            normalisation = 1.0,
            profiles = listOf(
                LiveSpeedPlayerProfile("Adolfo Daniel Vallejo", serveRate = 0.10, concedeRate = 0.08),
                LiveSpeedPlayerProfile("Daniel Merida Aguilar", serveRate = 0.09, concedeRate = 0.07),
            ),
        )
        val matches = (1..10).map {
            AtpMatchStats("D. VALLEJO", "D. MERIDA", 5, 80, 4, 82, true)
        }

        val coverage = AceRating.coverage(matches, realisticBaseline)
        val result = AceRating.rate(matches, realisticBaseline)!!

        assertEquals(20, coverage.serverProfilesMatched)
        assertEquals(20, coverage.returnerProfilesMatched)
        assertEquals(20, result.adjustedServerSides)
    }

    @Test
    fun `all 2026 Paris main-draw labels resolve to both player profile rates`() {
        val liveBaseline = ink.courtpace.index.core.data.PaceData.parse(Datasets.raw())
            .dataset.speedBaseline!!
        val parisDrawLabels = listOf(
            "A. ZVEREV", "L. MUSETTI", "H. HURKACZ", "L. VAN ASSCHE",
            "M. NAVONE", "D. VALLEJO", "L. TIEN", "N. DJOKOVIC",
            "V. VACHEROT", "L. DARDERI", "R. HIJIKATA", "A. RUBLEV",
            "T. ETCHEVERRY", "A. DE MINAUR", "B. SHELTON", "C. NORRIE",
            "M. ARNALDI", "M. BELLUCCI", "L. SONEGO", "A. RINDERKNECH",
            "F. CERUNDOLO", "T. PAUL", "H. RUNE", "D. MERIDA",
            "S. BAEZ", "Q. HALYS", "A. FERY", "F. TIAFOE",
            "T. TIRANTE", "J. STRUFF", "A. GEA", "C. RUUD",
            "J. CERUNDOLO", "A. TABILO", "R. JODAR", "N. BORGES",
            "M. KOUAME", "D. SHAPOVALOV", "K. KHACHANOV", "I. BUSE",
            "Z. BERGS", "D. MEDVEDEV", "A. FILS", "K. JACQUET",
            "T. MACHAC", "A. MOLCAN", "T. DROGUET", "B. BONZI",
            "T. FRITZ", "B. NAKASHIMA", "M. BERRETTINI", "U. HUMBERT",
            "B. VAN DE ZANDSCHULP", "A. MICHELSEN", "J. LEHECKA",
            "C. ALCARAZ",
        )
        assertEquals(56, parisDrawLabels.size)

        val matches = parisDrawLabels.chunked(2).map { pair ->
            AtpMatchStats(
                firstPlayer = pair[0],
                secondPlayer = pair[1],
                firstAces = 5,
                firstServePoints = 80,
                secondAces = 4,
                secondServePoints = 82,
                firstWon = true,
            )
        }
        val coverage = AceRating.coverage(matches, liveBaseline)
        val rating = AceRating.rate(matches, liveBaseline)!!

        assertEquals(56, coverage.totalServerSides)
        assertEquals(56, coverage.serverProfilesMatched)
        assertEquals(56, coverage.returnerProfilesMatched)
        assertEquals(56, coverage.adjustedServerSides)
        assertEquals(56, rating.adjustedServerSides)
    }

    @Test
    fun `ambiguous abbreviated names are not assigned to an arbitrary profile`() {
        val ambiguousBaseline = LiveSpeedBaseline(
            season = 2026,
            normalisation = 1.0,
            profiles = listOf(
                LiveSpeedPlayerProfile("Alex Smith", serveRate = 0.12, concedeRate = 0.10),
                LiveSpeedPlayerProfile("Andrew Smith", serveRate = 0.14, concedeRate = 0.08),
                LiveSpeedPlayerProfile("Bob Jones", serveRate = 0.11, concedeRate = 0.09),
            ),
        )
        val matches = (1..10).map {
            AtpMatchStats("A. SMITH", "B. JONES", 5, 39, 4, 40, true)
        }

        val coverage = AceRating.coverage(matches, ambiguousBaseline)

        assertEquals(0, coverage.adjustedServerSides)
        assertNull(AceRating.rate(matches, ambiguousBaseline))
    }

    @Test
    fun `possible server-side denominator grows with the completed match count`() {
        val fortyEight = AceRating.rate(sampleMatches(48), baseline)!!
        val fortyNine = AceRating.rate(sampleMatches(49), baseline)!!

        assertEquals(48, fortyEight.matches)
        assertEquals(96, fortyEight.totalServerSides)
        assertEquals(49, fortyNine.matches)
        assertEquals(98, fortyNine.totalServerSides)
    }

    @Test
    fun `includes every valid side with positive expected aces below half an ace`() {
        val lowAceBaseline = LiveSpeedBaseline(
            season = 2026,
            normalisation = 1.0,
            profiles = listOf(
                LiveSpeedPlayerProfile("A. Low", serveRate = 0.01, concedeRate = 0.01),
                LiveSpeedPlayerProfile("B. Low", serveRate = 0.01, concedeRate = 0.01),
            ),
        )
        val matches = (1..10).map {
            AtpMatchStats(
                firstPlayer = "A. Low",
                secondPlayer = "B. Low",
                firstAces = 1,
                firstServePoints = 80,
                secondAces = 1,
                secondServePoints = 82,
                firstWon = true,
            )
        }

        val coverage = AceRating.coverage(matches, lowAceBaseline)
        val result = AceRating.rate(matches, lowAceBaseline)!!

        assertEquals(20, coverage.totalServerSides)
        assertEquals(20, coverage.serverProfilesMatched)
        assertEquals(20, coverage.returnerProfilesMatched)
        assertEquals(20, coverage.adjustedServerSides)
        assertEquals(20, result.adjustedServerSides)
        assertEquals(2.0, result.rating, 1e-9)
    }

    @Test
    fun `zero expected aces remain excluded and never cause division by zero`() {
        val zeroBaseline = LiveSpeedBaseline(
            season = 2026,
            normalisation = 1.0,
            profiles = listOf(
                LiveSpeedPlayerProfile("A. Zero", serveRate = 0.10, concedeRate = 0.0),
                LiveSpeedPlayerProfile("B. Zero", serveRate = 0.10, concedeRate = 0.0),
            ),
        )

        val matches = (1..10).map {
            AtpMatchStats(
                firstPlayer = "A. Zero",
                secondPlayer = "B. Zero",
                firstAces = 6,
                firstServePoints = 40,
                secondAces = 6,
                secondServePoints = 40,
                firstWon = true,
                matchId = "ms-zero",
            )
        }
        val coverage = AceRating.coverage(matches, zeroBaseline)

        assertEquals(0, coverage.adjustedServerSides)
        assertEquals(20, coverage.issues.size)
        assertTrue(coverage.issues.all { it.matchId == "ms-zero" })
        assertTrue(coverage.issues.all { it.reason.contains("zero or invalid expected aces") })
        assertNull(AceRating.rate(matches, zeroBaseline))
    }

    @Test
    fun `every parsed match contributes both players service observations`() {
        val matches = sampleMatches(15)
        val result = AceRating.rate(matches, baseline)!!
        assertEquals(15, result.matches)
        assertEquals(30, result.adjustedServerSides)
        assertEquals(30, result.totalServerSides)
        assertEquals(12 * 15, result.aces)
        assertEquals(0.15, result.aceRate, 1e-9)
    }
}
