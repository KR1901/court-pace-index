package ink.courtpace.index.core

import ink.courtpace.index.core.playerstats.HeightGroupSummary
import ink.courtpace.index.core.playerstats.PlayerRankingMetric
import ink.courtpace.index.core.playerstats.PlayerStatsAnalytics
import ink.courtpace.index.core.playerstats.PlayerStatsBucket
import ink.courtpace.index.core.playerstats.PlayerStatsData
import ink.courtpace.index.core.playerstats.PlayerStatsMeta
import ink.courtpace.index.core.playerstats.PlayerStatsPlayer
import ink.courtpace.index.core.playerstats.PlayerSeasonStats
import ink.courtpace.index.core.playerstats.PlayerSurfaceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsAnalyticsTest {

    @Test
    fun `height cut points assign every supported height to one band`() {
        assertEquals("under183", PlayerStatsAnalytics.heightBandId(182))
        assertEquals("183to190", PlayerStatsAnalytics.heightBandId(183))
        assertEquals("183to190", PlayerStatsAnalytics.heightBandId(190))
        assertEquals("191to194", PlayerStatsAnalytics.heightBandId(191))
        assertEquals("191to194", PlayerStatsAnalytics.heightBandId(194))
        assertEquals("195plus", PlayerStatsAnalytics.heightBandId(195))
        assertNull(PlayerStatsAnalytics.heightBandId(null))
        assertNull(PlayerStatsAnalytics.heightBandId(221))
    }

    @Test
    fun `height text shows metres and feet inches`() {
        assertEquals("1.83 m · 6′0.0″", PlayerStatsAnalytics.heightText(183))
        assertEquals("1.95 m · 6′4.8″", PlayerStatsAnalytics.heightText(195))
        assertEquals("Height unavailable", PlayerStatsAnalytics.heightText(null))
    }

    @Test
    fun `height cohort uses current top 100 and pooled game denominators`() {
        val data = PlayerStatsData(
            meta = PlayerStatsMeta(playersInCurrentTop100 = 2),
            players = listOf(
                PlayerStatsPlayer(
                    id = "A", name = "Tall Player", heightCm = 195, currentRank = 1,
                    seasons = listOf(PlayerSeasonStats(2026, mapOf(
                        "all" to PlayerStatsBucket(mp = 10, w = 7, l = 3, sp = 100, sw = 90, rp = 100, rw = 25, sm = 10),
                    ))),
                ),
                PlayerStatsPlayer(
                    id = "B", name = "Short Player", heightCm = 180, currentRank = 2,
                    seasons = listOf(PlayerSeasonStats(2026, mapOf(
                        "all" to PlayerStatsBucket(mp = 2, w = 1, l = 1, sp = 10, sw = 5, rp = 10, rw = 4, sm = 2),
                    ))),
                ),
                PlayerStatsPlayer(
                    id = "C", name = "Not Current Top 100", heightCm = 190, currentRank = null,
                    seasons = listOf(PlayerSeasonStats(2026, mapOf(
                        "all" to PlayerStatsBucket(mp = 100, w = 90, l = 10, sp = 1000, sw = 990, rp = 1000, rw = 900, sm = 100),
                    ))),
                ),
            ),
        )

        val result = PlayerStatsAnalytics.heightGroups(
            data, 2026, PlayerSurfaceFilter.ALL,
        )
        val short = result.groups.first { it.id == "under183" }
        val tall = result.groups.first { it.id == "195plus" }

        assertEquals(2, result.cohortSize)
        assertEquals(2, result.heightKnown)
        assertEquals(1, short.players)
        assertEquals(1, tall.players)
        assertEquals(50.0, short.holdPct!!, 0.001)
        assertEquals(40.0, short.breakPct!!, 0.001)
        assertEquals(90.0, tall.holdPct!!, 0.001)
        assertEquals(25.0, tall.breakPct!!, 0.001)
        assertEquals(10, tall.serviceGames)
        assertEquals(2, tall.wins + tall.losses)
    }

    @Test
    fun `height group averages are weighted by games not player percentages`() {
        val data = PlayerStatsData(
            PlayerStatsMeta(playersInCurrentTop100 = 2),
            listOf(
                PlayerStatsPlayer("A", "Player A",  currentRank = 1, heightCm = 195, seasons = listOf(
                    PlayerSeasonStats(2026, mapOf("all" to PlayerStatsBucket(sp = 100, sw = 90, rp = 100, rw = 30))),
                )),
                PlayerStatsPlayer("B", "Player B", currentRank = 2, heightCm = 198, seasons = listOf(
                    PlayerSeasonStats(2026, mapOf("all" to PlayerStatsBucket(sp = 10, sw = 5, rp = 10, rw = 5))),
                )),
            ),
        )

        val group = PlayerStatsAnalytics.heightGroups(
            data, 2026, PlayerSurfaceFilter.ALL,
        ).groups.first { it.id == "195plus" }

        assertEquals(95.0 / 110.0 * 100.0, group.holdPct!!, 0.001)
        assertEquals(35.0 / 110.0 * 100.0, group.breakPct!!, 0.001)
    }

    @Test
    fun `rankings apply metric and games qualification`() {
        val data = PlayerStatsData(
            PlayerStatsMeta(),
            listOf(
                PlayerStatsPlayer("A", "High sample", seasons = listOf(
                    PlayerSeasonStats(2026, mapOf("all" to PlayerStatsBucket(sp = 200, sw = 180, rp = 200, rw = 50))),
                )),
                PlayerStatsPlayer("B", "Low sample", seasons = listOf(
                    PlayerSeasonStats(2026, mapOf("all" to PlayerStatsBucket(sp = 20, sw = 19, rp = 20, rw = 10))),
                )),
            ),
        )

        val rows = PlayerStatsAnalytics.rankings(
            data, 2026, PlayerSurfaceFilter.ALL, PlayerRankingMetric.HOLD, minimumGames = 100,
        )
        assertEquals("High sample", rows[0].player.name)
        assertEquals(1, rows[0].rank)
        assertTrue(rows[0].qualified)
        assertEquals("Low sample", rows[1].player.name)
        assertNull(rows[1].rank)
        assertTrue(!rows[1].qualified)

        val combined = PlayerStatsAnalytics.rankings(
            data, 2026, PlayerSurfaceFilter.ALL, PlayerRankingMetric.COMBINED, minimumGames = 10,
        )
        assertEquals(145.0, combined[0].combined!!, 0.001)
    }

    @Test
    fun `missing season and surface do not receive invented values`() {
        val data = PlayerStatsData(
            PlayerStatsMeta(),
            listOf(PlayerStatsPlayer("A", "Player A", heightCm = null)),
        )
        val result = PlayerStatsAnalytics.rankings(
            data, 2026, PlayerSurfaceFilter.INDOOR_HARD, PlayerRankingMetric.HOLD, 0,
        )
        assertEquals(1, result.size)
        assertNull(result.single().holdPct)
        assertNull(result.single().score)
        assertTrue(!result.single().qualified)
    }
}
