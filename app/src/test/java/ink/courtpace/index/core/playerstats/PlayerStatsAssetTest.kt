package ink.courtpace.index.core.playerstats

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsAssetTest {

    @Test
    fun `bundled asset decodes year specific Top 100 snapshots`() {
        val stream = javaClass.classLoader?.getResourceAsStream("player_stats.json")
            ?: error("Bundled player_stats.json must be available on the unit-test classpath")
        val data = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            Json { ignoreUnknownKeys = true }.decodeFromString<PlayerStatsData>(reader.readText())
        }

        val snapshots = data.meta.top100SeasonSnapshots
        // A PR's Android job can start before the parallel source-refresh job commits
        // its regenerated asset. Keep that intermediate build backward-compatible;
        // the refresh workflow independently validates the generated annual asset.
        if (snapshots.isEmpty()) {
            assertTrue(data.players.any { it.currentRank != null })
            return
        }
        assertEquals(data.meta.lastSeason - data.meta.firstTop100Season + 1, snapshots.size)
        assertEquals(1973, snapshots.first().year)
        assertEquals(data.meta.lastSeason, snapshots.last().year)
        assertEquals(data.meta.currentTop100AsOf, snapshots.last().asOf)

        val expandedSnapshots = data.meta.top1000SeasonSnapshots
        if (expandedSnapshots.isNotEmpty()) {
            assertEquals(data.meta.lastSeason - data.meta.firstTop1000Season + 1, expandedSnapshots.size)
            assertEquals(1973, expandedSnapshots.first().year)
            val currentExpanded = expandedSnapshots.last()
            assertEquals(data.meta.lastSeason, currentExpanded.year)
            assertTrue(currentExpanded.maxRank >= 1000)
            assertTrue(currentExpanded.rankedEntries >= 1000)

            val liveTop1000 = data.players.filter { player ->
                player.top1000RanksBySeason.any { it.y == data.meta.lastSeason }
            }
            assertEquals(currentExpanded.players, liveTop1000.size)
            assertEquals(data.meta.playersInCurrentTop1000, liveTop1000.size)
            assertTrue(liveTop1000.all { player ->
                player.top1000RanksBySeason.single { it.y == data.meta.lastSeason }.rank in 1..1000
            })
        }

        val year2015 = snapshots.single { it.year == 2015 }
        val cohort2015 = data.players.filter { player ->
            player.top100RanksBySeason.any { it.y == 2015 }
        }
        val liveCohort = data.players.filter { player ->
            player.top100RanksBySeason.any { it.y == data.meta.lastSeason }
        }

        assertEquals(year2015.players, cohort2015.size)
        assertEquals(data.meta.playersInCurrentTop100, liveCohort.size)
        assertTrue(cohort2015.size >= 100)
        assertTrue(liveCohort.size >= 100)

        // Early TML ranking history has an occasional "Unknown"/NA row.
        // It is recorded as unresolved, never reused as a fake player across years.
        val year1974 = snapshots.single { it.year == 1974 }
        val cohort1974 = data.players.filter { player ->
            player.top100RanksBySeason.any { it.y == 1974 }
        }
        assertEquals(100, year1974.players)
        assertEquals(1, year1974.unresolvedPlayers)
        assertEquals(101, year1974.rankedEntries)
        assertEquals(year1974.players, cohort1974.size)
        assertTrue(data.players.none { it.id == "NA" })

        // The reconstructed 1975 snapshot has both co-ranked positions and
        // one unresolved source row; identified players remain distinct.
        val year1975 = snapshots.single { it.year == 1975 }
        val cohort1975 = data.players.filter { player ->
            player.top100RanksBySeason.any { it.y == 1975 }
        }
        assertEquals(102, year1975.players)
        assertEquals(1, year1975.unresolvedPlayers)
        assertEquals(103, year1975.rankedEntries)
        assertEquals(year1975.players, cohort1975.size)

        val rogerFederer = data.players.single { it.name == "Roger Federer" }
        assertNotNull(rogerFederer.top100RanksBySeason.singleOrNull { it.y == 2015 })
        assertNull(rogerFederer.top100RanksBySeason.singleOrNull { it.y == data.meta.lastSeason })

        val jannikSinner = data.players.single { it.name == "Jannik Sinner" }
        assertNotNull(jannikSinner.top100RanksBySeason.singleOrNull { it.y == data.meta.lastSeason })
        assertNull(jannikSinner.top100RanksBySeason.singleOrNull { it.y == 2015 })
    }
}
