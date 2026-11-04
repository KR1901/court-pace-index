package ink.courtpace.index.core

import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bundled dataset itself: a typo in a tournament id, a value off by a factor of
 * ten, or a duplicated reading would otherwise surface as a strange number on screen.
 */
class DatasetTest {

    private val data = PaceData.parse(Datasets.raw())

    @Test
    fun `dataset parses with meta and both sources`() {
        assertEquals(2026, data.dataset.meta.season)
        assertEquals(3, data.dataset.meta.sources.size)
        assertTrue(data.dataset.meta.sources.all { it.url.startsWith("https://") })
    }

    @Test
    fun `tournament ids are unique and complete`() {
        val ids = data.tournaments.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        data.tournaments.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.city.isNotBlank())
            assertEquals(3, it.country.length)
        }
    }

    @Test
    fun `every reading points at a real tournament`() {
        val orphans = data.dataset.readings.filter { data.byId[it.t] == null }
        assertTrue("orphan readings: ${orphans.map { it.t }}", orphans.isEmpty())
    }

    @Test
    fun `readings carry exactly one metric each`() {
        data.dataset.readings.forEach { reading ->
            val present = listOfNotNull(reading.cpi, reading.speed).size
            assertEquals("reading $reading", 1, present)
            assertTrue(reading.s in 1990..2030)
            assertTrue(reading.src.isNotBlank())
        }
    }

    @Test
    fun `values sit inside plausible ranges`() {
        data.dataset.readings.forEach { reading ->
            reading.cpi?.let { assertTrue("cpi $it at ${reading.t}", it in 10.0..60.0) }
            reading.speed?.let { assertTrue("speed $it at ${reading.t}", it in 0.30..2.0) }
            reading.ace?.let { assertTrue("ace $it at ${reading.t}", it in 0.0..30.0) }
        }
    }

    @Test
    fun `no duplicate readings per tournament and season`() {
        val keys = data.dataset.readings.map { Triple(it.t, it.s, it.src) }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `coverage matches what the sources claim`() {
        val cpi = data.dataset.readings.count { it.cpi != null }
        val speed = data.dataset.readings.count { it.speed != null }
        assertEquals(106, cpi)
        assertEquals(497, speed)
        assertEquals(2012, data.seasons.first())
        assertEquals(2026, data.seasons.last())
    }

    @Test
    fun `speed history is split cleanly between published and computed`() {
        val published = data.dataset.readings.filter { it.speed != null && it.src == "tennisabstract" }
        val computed = data.dataset.readings.filter { it.speed != null && it.src == "computed" }
        assertEquals(62, published.size)
        assertEquals(435, computed.size)
        // The published table is a rolling 52-week window, so it straddles two seasons.
        assertEquals(setOf(2025, 2026), published.map { it.s }.toSet())
        assertTrue(computed.all { it.s in 2012..2025 })
        // No season is claimed twice for the same event.
        val publishedKeys = published.map { it.t to it.s }.toSet()
        assertTrue(computed.none { (it.t to it.s) in publishedKeys })
    }

    @Test
    fun `computed history covers most of the tour in a middle season`() {
        val events2019 = data.tournaments.count { data.value(it.id, 2019, Metric.SPEED) != null }
        assertTrue("only $events2019 events rated in 2019", events2019 >= 30)
        val events2013 = data.tournaments.count { data.value(it.id, 2013, Metric.SPEED) != null }
        assertTrue("only $events2013 events rated in 2013", events2013 >= 20)
    }

    @Test
    fun `2026 has enough overlap to compare the two metrics`() {
        val both = data.tournaments.count {
            data.value(it.id, 2026, Metric.CPI) != null && data.value(it.id, 2026, Metric.SPEED) != null
        }
        assertTrue("only $both events have both metrics", both >= 8)
    }

    @Test
    fun `every tournament has at least one reading`() {
        data.tournaments.forEach { tournament ->
            assertTrue("${tournament.id} has no readings", data.readingsFor(tournament.id).isNotEmpty())
        }
    }
}
