package ink.courtpace.index.core

import ink.courtpace.index.core.playerstats.HeightGroupCohort
import ink.courtpace.index.core.playerstats.HeightGroupSummary
import ink.courtpace.index.core.playerstats.PlayerRankingMetric
import ink.courtpace.index.core.playerstats.PlayerStatsAnalytics
import ink.courtpace.index.core.playerstats.PlayerStatsBucket
import ink.courtpace.index.core.playerstats.PlayerStatsData
import ink.courtpace.index.core.playerstats.PlayerStatsDateTime
import ink.courtpace.index.core.playerstats.PlayerStatsMeta
import ink.courtpace.index.core.playerstats.PlayerStatsPlayer
import ink.courtpace.index.core.playerstats.PlayerTop100SeasonRank
import ink.courtpace.index.core.playerstats.PlayerTop1000SeasonRank
import ink.courtpace.index.core.playerstats.PlayerTop1000SeasonSnapshot
import ink.courtpace.index.core.playerstats.PlayerSeasonStats
import ink.courtpace.index.core.playerstats.PlayerSurfaceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsAnalyticsTest {

    @Test
    fun `height cut points assign every supported height to one band`() {
        assertEquals("under183", PlayerStatsAnalytics.heightBandId(140))
        assertEquals("under183", PlayerStatsAnalytics.heightBandId(182))
        assertEquals("183to185", PlayerStatsAnalytics.heightBandId(183))
        assertEquals("183to185", PlayerStatsAnalytics.heightBandId(185))
        assertEquals("186to188", PlayerStatsAnalytics.heightBandId(186))
        assertEquals("186to188", PlayerStatsAnalytics.heightBandId(188))
        assertEquals("189to191", PlayerStatsAnalytics.heightBandId(189))
        assertEquals("189to191", PlayerStatsAnalytics.heightBandId(191))
        assertEquals("192to194", PlayerStatsAnalytics.heightBandId(192))
        assertEquals("192to194", PlayerStatsAnalytics.heightBandId(194))
        assertEquals("195plus", PlayerStatsAnalytics.heightBandId(195))
        assertEquals("195plus", PlayerStatsAnalytics.heightBandId(220))
        assertNull(PlayerStatsAnalytics.heightBandId(null))
        assertNull(PlayerStatsAnalytics.heightBandId(139))
        assertNull(PlayerStatsAnalytics.heightBandId(221))
    }

    @Test
    fun `height groups expose the six requested non-overlapping bands`() {
        val result = PlayerStatsAnalytics.heightGroups(
            PlayerStatsData(PlayerStatsMeta(lastSeason = 2026), emptyList()),
            2026,
            PlayerSurfaceFilter.ALL,
        )
        assertEquals(
            listOf("under183", "183to185", "186to188", "189to191", "192to194", "195plus"),
            result.groups.map { it.id },
        )
        assertEquals(
            listOf(
                null to 182,
                183 to 185,
                186 to 188,
                189 to 191,
                192 to 194,
                195 to null,
            ),
            result.groups.map { it.minCm to it.maxCm },
        )
        assertEquals(
            listOf(
                "Under 1.83 m / under 6′0″",
                "1.83–1.85 m / 6′0.0″–6′0.8″",
                "1.86–1.88 m / 6′1.2″–6′2.0″",
                "1.89–1.91 m / 6′2.4″–6′3.2″",
                "1.92–1.94 m / 6′3.6″–6′4.4″",
                "1.95 m+ / approximately 6′5″+",
            ),
            result.groups.map { it.label },
        )
    }

    @Test
    fun `player stats dates use Indian calendar format and timestamps use IST`() {
        assertEquals("11-11-26", PlayerStatsDateTime.formatDate("2026-11-11"))
        assertEquals("12-11-26", PlayerStatsDateTime.formatDate("2026-11-11T20:00:00Z"))
        assertEquals(
            "12-11-26 09:56:04 IST",
            PlayerStatsDateTime.formatTimestamp("2026-11-12T04:26:04Z"),
        )
        assertEquals(
            "12-11-26 08:18 IST",
            PlayerStatsDateTime.formatTimestamp("2026-11-12T02:48:00Z", includeSeconds = false),
        )
        assertEquals(
            "12-11-26 10:00:00 IST",
            PlayerStatsDateTime.formatTimestamp("2026-11-12T10:00:00+05:30"),
        )
        assertNull(PlayerStatsDateTime.formatDate(null))
        assertNull(PlayerStatsDateTime.formatTimestamp("not-a-timestamp"))
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
            meta = PlayerStatsMeta(lastSeason = 2026, playersInCurrentTop100 = 2),
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
        assertEquals(1, short.playersWithSeasonData)
        assertEquals(1, short.playersWithValidStats)
        assertEquals(1, tall.players)
        assertEquals(1, tall.playersWithValidStats)
        assertEquals(50.0, short.holdPct!!, 0.001)
        assertEquals(40.0, short.breakPct!!, 0.001)
        assertEquals(90.0, tall.holdPct!!, 0.001)
        assertEquals(25.0, tall.breakPct!!, 0.001)
        assertEquals(100, tall.serviceGames)
        assertEquals(10, tall.wins + tall.losses)
    }

    @Test
    fun `historical height groups use that years year end top 100 not the live cohort`() {
        val data = PlayerStatsData(
            meta = PlayerStatsMeta(lastSeason = 2026, playersInCurrentTop100 = 2),
            players = listOf(
                PlayerStatsPlayer(
                    id = "A", name = "Historical and live member", heightCm = 195,
                    currentRank = 1,
                    seasons = listOf(PlayerSeasonStats(2015, mapOf(
                        "all" to PlayerStatsBucket(mp = 10, w = 7, l = 3, sp = 100, sw = 90, rp = 100, rw = 20, sm = 10),
                    ))),
                    top100RanksBySeason = listOf(
                        PlayerTop100SeasonRank(2015, 12),
                        PlayerTop100SeasonRank(2026, 1),
                    ),
                ),
                PlayerStatsPlayer(
                    id = "B", name = "2015 member only", heightCm = 180,
                    seasons = listOf(PlayerSeasonStats(2015, mapOf(
                        "all" to PlayerStatsBucket(mp = 8, w = 5, l = 3, sp = 80, sw = 64, rp = 80, rw = 24, sm = 8),
                    ))),
                    top100RanksBySeason = listOf(PlayerTop100SeasonRank(2015, 88)),
                ),
                PlayerStatsPlayer(
                    id = "C", name = "Live member only", heightCm = 192, currentRank = 2,
                    seasons = listOf(PlayerSeasonStats(2015, mapOf(
                        "all" to PlayerStatsBucket(mp = 50, w = 40, l = 10, sp = 500, sw = 450, rp = 500, rw = 150, sm = 50),
                    ))),
                    top100RanksBySeason = listOf(PlayerTop100SeasonRank(2026, 2)),
                ),
                PlayerStatsPlayer(
                    id = "D", name = "Historical member without a known height",
                    top100RanksBySeason = listOf(PlayerTop100SeasonRank(2015, 100)),
                ),
            ),
        )

        val historical = PlayerStatsAnalytics.heightGroups(data, 2015, PlayerSurfaceFilter.ALL)
        val live = PlayerStatsAnalytics.heightGroups(data, 2026, PlayerSurfaceFilter.ALL)
        val historicalShort = historical.groups.first { it.id == "under183" }
        val historicalTall = historical.groups.first { it.id == "195plus" }

        assertEquals(3, historical.cohortSize)
        assertEquals(2, historical.heightKnown)
        assertEquals(1, historical.unassignedHeight)
        assertEquals(1, historicalShort.players)
        assertEquals(1, historicalTall.players)
        assertEquals(1, historicalShort.playersWithValidStats)
        assertEquals(1, historicalTall.playersWithValidStats)
        assertEquals(2, live.cohortSize)
        assertEquals(1, live.groups.first { it.id == "192to194" }.players)
        assertEquals(1, live.groups.first { it.id == "195plus" }.players)
    }

    @Test
    fun `height groups use the selected year-specific top 100 500 and 1000 ranks`() {
        val ranks = listOf(1, 100, 101, 500, 501, 1000)
        val data = PlayerStatsData(
            meta = PlayerStatsMeta(
                lastSeason = 2026,
                top1000SeasonSnapshots = listOf(
                    PlayerTop1000SeasonSnapshot(
                        year = 2026,
                        asOf = "2026-11-09",
                        source = "https://stats.tennismylife.org/api/ranking?date=2026-11-09",
                        players = 6,
                        rankedEntries = 6,
                        maxRank = 1000,
                    ),
                ),
            ),
            players = ranks.mapIndexed { index, rank ->
                PlayerStatsPlayer(
                    id = "P$index",
                    name = "Player $rank",
                    heightCm = 180 + index * 3,
                    seasons = listOf(
                        PlayerSeasonStats(2026, mapOf(
                            "all" to PlayerStatsBucket(
                                mp = 10, w = 7, l = 3,
                                sp = 100, sw = 80 + index,
                                rp = 100, rw = 20 + index,
                                sm = 10,
                            ),
                        )),
                    ),
                    top100RanksBySeason = if (rank <= 100) {
                        listOf(PlayerTop100SeasonRank(2026, rank))
                    } else {
                        emptyList()
                    },
                    top1000RanksBySeason = listOf(PlayerTop1000SeasonRank(2026, rank)),
                )
            },
        )

        val top100 = PlayerStatsAnalytics.heightGroups(
            data, 2026, PlayerSurfaceFilter.ALL, HeightGroupCohort.TOP_100,
        )
        val top500 = PlayerStatsAnalytics.heightGroups(
            data, 2026, PlayerSurfaceFilter.ALL, HeightGroupCohort.TOP_500,
        )
        val top1000 = PlayerStatsAnalytics.heightGroups(
            data, 2026, PlayerSurfaceFilter.ALL, HeightGroupCohort.TOP_1000,
        )

        assertEquals(2, top100.cohortSize)
        assertEquals(4, top500.cohortSize)
        assertEquals(6, top1000.cohortSize)
        assertTrue(top100.cohortAvailable)
        assertTrue(top500.cohortAvailable)
        assertTrue(top1000.cohortAvailable)
        assertEquals(1000, top1000.availableThroughRank)
        assertEquals(6, top1000.groups.sumOf { it.players } + top1000.unassignedHeight)
    }

    @Test
    fun `height groups do not label an incomplete source snapshot as top 500 or top 1000`() {
        val data = PlayerStatsData(
            meta = PlayerStatsMeta(
                lastSeason = 2026,
                top1000SeasonSnapshots = listOf(
                    PlayerTop1000SeasonSnapshot(
                        year = 2026,
                        asOf = "2026-11-09",
                        source = "https://stats.tennismylife.org/api/ranking?date=2026-11-09",
                        players = 2,
                        rankedEntries = 2,
                        maxRank = 350,
                    ),
                ),
            ),
            players = listOf(
                PlayerStatsPlayer(
                    id = "A", name = "Top 1", heightCm = 195,
                    top1000RanksBySeason = listOf(PlayerTop1000SeasonRank(2026, 1)),
                ),
                PlayerStatsPlayer(
                    id = "B", name = "Top 350", heightCm = 180,
                    top1000RanksBySeason = listOf(PlayerTop1000SeasonRank(2026, 350)),
                ),
            ),
        )

        val top100 = PlayerStatsAnalytics.heightGroups(
            data, 2026, PlayerSurfaceFilter.ALL, HeightGroupCohort.TOP_100,
        )
        val top500 = PlayerStatsAnalytics.heightGroups(
            data, 2026, PlayerSurfaceFilter.ALL, HeightGroupCohort.TOP_500,
        )
        val top1000 = PlayerStatsAnalytics.heightGroups(
            data, 2026, PlayerSurfaceFilter.ALL, HeightGroupCohort.TOP_1000,
        )

        assertTrue(top100.cohortAvailable)
        assertEquals(1, top100.cohortSize)
        assertTrue(!top500.cohortAvailable)
        assertTrue(!top1000.cohortAvailable)
        assertEquals(0, top500.cohortSize)
        assertEquals(0, top1000.cohortSize)
    }

    @Test
    fun `height group averages are weighted by games not player percentages`() {
        val data = PlayerStatsData(
            PlayerStatsMeta(lastSeason = 2026, playersInCurrentTop100 = 2),
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
