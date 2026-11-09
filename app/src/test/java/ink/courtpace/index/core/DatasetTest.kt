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
    fun `readings carry one primary metric or one provisional diagnostic`() {
        data.dataset.readings.forEach { reading ->
            val primary = listOfNotNull(reading.cpi, reading.speed).size
            val provisionalOnly = reading.provisionalSpeed != null &&
                reading.cpi == null && reading.speed == null
            assertTrue("reading has invalid primary/provisional metric shape: $reading",
                primary == 1 || provisionalOnly)
            assertTrue(reading.s in 1990..2030)
            assertTrue(reading.src.isNotBlank())
            reading.provisionalSpeed?.let {
                assertTrue(it in 0.30..2.0)
                assertTrue(reading.provisionalServerSides!! in 10..19)
                assertTrue(reading.provisionalCiLow!! <= reading.provisionalCiHigh!!)
                if (reading.speed == null) assertEquals("provisional", reading.src)
                else assertTrue(reading.src == "tennisabstract" || reading.src == "computed")
            }
            if (reading.speedCiLow != null && reading.speedCiHigh != null) {
                assertTrue(reading.speedCiLow <= reading.speedCiHigh)
                assertEquals("match-cluster-conditional-v1", reading.speedCiMethod)
            }
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
        assertTrue("CPI coverage dropped below its 106-reading baseline: $cpi", cpi >= 106)
        assertTrue("Speed coverage dropped below its 716-reading baseline: $speed", speed >= 716)
        assertEquals(2012, data.seasons.first())
        assertEquals(2026, data.seasons.last())
    }

    @Test
    fun `calibrated readings lead and published values remain available as comparisons`() {
        val published = data.dataset.readings.filter {
            it.speed != null && (it.src == "tennisabstract" || it.publishedSpeed != null)
        }
        val computed = data.dataset.readings.filter { it.speed != null && it.src == "computed" }
        assertTrue("published coverage unexpectedly shrank: ${published.size}", published.size >= 62)
        assertTrue("calculated coverage unexpectedly shrank: ${computed.size}", computed.size >= 685)
        assertEquals(setOf(2025, 2026), published.map { it.s }.toSet())
        assertTrue(computed.all { it.s in 2012..2026 })
        val comparisons = computed.filter { it.publishedSpeed != null }
        assertTrue("too few published/calculated comparisons: ${comparisons.size}", comparisons.size >= 31)
        val montpellier = comparisons.first {
            it.t == "montpellier" && it.s == 2026
        }
        assertEquals(montpellier.speed, data.value("montpellier", 2026, Metric.SPEED))
        assertEquals(montpellier.publishedSpeed!!, data.publishedSpeed("montpellier", 2026)!!, 0.001)
        assertEquals(1.53, montpellier.publishedSpeed!!, 0.001)
        assertEquals("computed", data.speedSource("montpellier", 2026))
        // Laver Cup's 2026 seven-match draw has fourteen valid adjusted server sides.
        // It now receives a ranked CALC point estimate, with the 2026 TA value retained.
        val laverCup = data.dataset.readings.first {
            it.t == "lavercup" && it.s == 2026
        }
        assertEquals("computed", laverCup.src)
        assertEquals(7, laverCup.matches)
        assertEquals(14, laverCup.adjustedServerSides)
        assertEquals(0.44, laverCup.publishedSpeed!!, 0.001)
        assertEquals("computed", data.speedSource("lavercup", 2026))
        assertEquals(laverCup.speed, data.value("lavercup", 2026, Metric.SPEED))
        assertEquals(laverCup.publishedSpeed, data.publishedSpeed("lavercup", 2026))
    }

    @Test
    fun `Laver Cup host city is resolved per edition without rewriting history`() {
        val edition2025 = data.tournament("lavercup", 2025)!!
        val edition2026 = data.tournament("lavercup", 2026)!!

        assertEquals("San Francisco", edition2025.city)
        assertEquals("USA", edition2025.country)
        assertEquals("London", edition2026.city)
        assertEquals("GBR", edition2026.country)
        assertEquals("London", data.entries(2026, Metric.SPEED)
            .first { it.first.id == "lavercup" }.first.city)
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
