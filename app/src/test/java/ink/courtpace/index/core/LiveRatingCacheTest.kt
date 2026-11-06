package ink.courtpace.index.core

import ink.courtpace.index.core.live.CachedLiveRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRatingCacheTest {

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
    fun `a legacy pooled-rate cache is recognised as an old method`() {
        val legacy = """{"t":"paris","s":2026,"speed":1.47,"matches":47,"ace":11.5,"asOf":"2026-11-06T00:00:00Z"}"""
        val restored = CachedLiveRating.fromJson(legacy)!!
        assertEquals(CachedLiveRating.LEGACY_METHOD_VERSION, restored.methodVersion)
        assertEquals(false, restored.methodVersion == CachedLiveRating.CURRENT_METHOD_VERSION)
    }

}
