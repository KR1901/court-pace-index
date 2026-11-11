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
        assertEquals(data.meta.lastSeason - data.meta.firstTop100Season + 1, snapshots.size)
        assertEquals(1973, snapshots.first().year)
        assertEquals(data.meta.lastSeason, snapshots.last().year)
        assertEquals(data.meta.currentTop100AsOf, snapshots.last().asOf)

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

        val rogerFederer = data.players.single { it.name == "Roger Federer" }
        assertNotNull(rogerFederer.top100RanksBySeason.singleOrNull { it.y == 2015 })
        assertNull(rogerFederer.top100RanksBySeason.singleOrNull { it.y == data.meta.lastSeason })

        val jannikSinner = data.players.single { it.name == "Jannik Sinner" }
        assertNotNull(jannikSinner.top100RanksBySeason.singleOrNull { it.y == data.meta.lastSeason })
        assertNull(jannikSinner.top100RanksBySeason.singleOrNull { it.y == 2015 })
    }
}
