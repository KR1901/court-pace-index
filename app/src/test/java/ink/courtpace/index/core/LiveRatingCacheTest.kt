package ink.courtpace.index.core

import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.CachedLiveRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRatingCacheTest {

    private fun skippedSides(count: Int) = (1..count).map { index ->
        AceRating.AdjustmentIssue(
            matchId = "ms${index.toString().padStart(3, '0')}",
            server = "A. Server",
            returner = "B. Returner",
            reason = "no returner concession profile",
        )
    }

    @Test
    fun `a rating survives the round trip`() {
        val original = CachedLiveRating("paris", 2026, 1.043, 31, 8.2, "2026-11-05T07:00:00Z")
        val restored = CachedLiveRating.fromJson(original.toJson())!!
        assertEquals(original, restored)
    }

    @Test
    fun `a missing ace rate is tolerated`() {
        val original = CachedLiveRating("paris", 2026, 0.97, 12, null, "2026-11-05T07:00:00Z")
        assertEquals(original, CachedLiveRating.fromJson(original.toJson())!!)
    }

    @Test
    fun `a corrupt or absent cache yields nothing rather than throwing`() {
        assertNull(CachedLiveRating.fromJson(null))
        assertNull(CachedLiveRating.fromJson(""))
        assertNull(CachedLiveRating.fromJson("not json at all"))
        assertNull(CachedLiveRating.fromJson("{\"t\":\"paris\"}"))
    }
    @Test
    fun `side coverage and discovered-match counts survive a cache round trip`() {
        val original = CachedLiveRating(
            tournamentId = "paris",
            season = 2026,
            speed = 1.41,
            matches = 48,
            aceRate = 0.112,
            asOf = "2026-11-06T17:00:00Z",
            baselineGenerated = "2026-11-06T16:34:12Z",
            discoveredMatches = 48,
            adjustedServerSides = 91,
            totalServerSides = 96,
            aces = 512,
            adjustmentIssues = skippedSides(5),
        )

        val restored = CachedLiveRating.fromJson(original.toJson())!!

        assertEquals(original, restored)
        val result = restored.toAceRatingResultOrNull()!!
        assertEquals(48, result.matches)
        assertEquals(48, result.discoveredMatches)
        assertEquals(91, result.adjustedServerSides)
        assertEquals(96, result.totalServerSides)
        assertEquals(512, result.aces)
    }

    @Test
    fun `excluded player-side diagnostics survive live rating cache round trip`() {
        val issue = ink.courtpace.index.core.live.AceRating.AdjustmentIssue(
            matchId = "ms043",
            server = "A. RINDERKNECH",
            returner = "F. CERUNDOLO",
            reason = "no returner concession profile",
        )
        val original = CachedLiveRating(
            tournamentId = "paris",
            season = 2026,
            speed = 1.61,
            matches = 49,
            aceRate = 0.10,
            asOf = "2026-11-07T00:00:00Z",
            methodVersion = CachedLiveRating.CURRENT_METHOD_VERSION,
            baselineGenerated = "2026-11-06T19:25:21Z",
            discoveredMatches = 49,
            adjustedServerSides = 97,
            totalServerSides = 98,
            adjustmentIssues = listOf(issue),
        )

        val restored = CachedLiveRating.fromJson(original.toJson())!!

        assertEquals(original, restored)
        val result = restored.toAceRatingResultOrNull()!!
        assertEquals(listOf(issue), result.adjustmentIssues)
    }

    @Test
    fun `server-side denominator includes completed matches whose stats are still missing`() {
        val partial = CachedLiveRating(
            tournamentId = "paris",
            season = 2026,
            speed = 1.41,
            matches = 40,
            aceRate = 0.112,
            asOf = "2026-11-06T17:00:00Z",
            baselineGenerated = "2026-11-06T16:34:12Z",
            discoveredMatches = 48,
            adjustedServerSides = 70,
            totalServerSides = 96,
            aces = 420,
            adjustmentIssues = skippedSides(10),
        )

        val restored = CachedLiveRating.fromJson(partial.toJson())!!
        val result = restored.toAceRatingResultOrNull()!!

        assertEquals(40, result.matches)
        assertEquals(48, result.discoveredMatches)
        assertEquals(70, result.adjustedServerSides)
        assertEquals(96, result.totalServerSides)
    }

    @Test
    fun `an old cache without side coverage is not presented as fully covered`() {
        val legacy = """{"t":"paris","s":2026,"speed":1.41,"matches":48,"ace":0.112,"asOf":"2026-11-06T17:00:00Z","methodVersion":2,"baselineGenerated":"2026-11-06T16:34:12Z"}"""
        val restored = CachedLiveRating.fromJson(legacy)!!

        assertNull(restored.adjustedServerSides)
        assertNull(restored.totalServerSides)
        assertNull(restored.toAceRatingResultOrNull())
    }

    @Test
    fun `a partial 50 of 98 cache without per-side reasons is rejected for recomputation`() {
        val stale = CachedLiveRating(
            tournamentId = "paris",
            season = 2026,
            speed = 1.48,
            matches = 49,
            aceRate = 0.10,
            asOf = "2026-11-07T00:00:00Z",
            methodVersion = CachedLiveRating.CURRENT_METHOD_VERSION,
            baselineGenerated = "2026-11-06T19:59:51Z",
            discoveredMatches = 49,
            adjustedServerSides = 50,
            totalServerSides = 98,
        )

        assertNull(stale.toAceRatingResultOrNull())
    }

    @Test
    fun `cache rejects impossible adjusted-side coverage`() {
        val invalid = CachedLiveRating(
            tournamentId = "paris",
            season = 2026,
            speed = 1.41,
            matches = 48,
            aceRate = 0.112,
            asOf = "2026-11-06T17:00:00Z",
            discoveredMatches = 48,
            adjustedServerSides = 97,
            totalServerSides = 96,
        )

        assertNull(invalid.toAceRatingResultOrNull())
    }

    @Test
    fun `the previous live-rating cache is invalidated after formula calibration changes`() {
        val previous = CachedLiveRating(
            tournamentId = "paris",
            season = 2026,
            speed = 1.61,
            matches = 49,
            aceRate = 0.10,
            asOf = "2026-11-07T00:00:00Z",
            methodVersion = 5,
            baselineGenerated = "2026-11-06T19:25:21Z",
            discoveredMatches = 49,
            adjustedServerSides = 94,
            totalServerSides = 98,
        )

        val restored = CachedLiveRating.fromJson(previous.toJson())!!

        assertEquals(5, restored.methodVersion)
        assertEquals(6, CachedLiveRating.CURRENT_METHOD_VERSION)
        assertTrue(restored.methodVersion != CachedLiveRating.CURRENT_METHOD_VERSION)
        assertNull(restored.toAceRatingResultOrNull())
    }

    @Test
    fun `a legacy pooled-rate cache is recognised as an old method`() {
        val legacy = """{"t":"paris","s":2026,"speed":1.47,"matches":47,"ace":11.5,"asOf":"2026-11-06T00:00:00Z"}"""
        val restored = CachedLiveRating.fromJson(legacy)!!
        assertEquals(CachedLiveRating.LEGACY_METHOD_VERSION, restored.methodVersion)
        assertEquals(false, restored.methodVersion == CachedLiveRating.CURRENT_METHOD_VERSION)
        assertNull(restored.toAceRatingResultOrNull())
    }

}
