package ink.courtpace.index.core

import ink.courtpace.index.core.analytics.PaceAnalytics
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.DatasetMeta
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.PaceDataset
import ink.courtpace.index.core.model.PaceReading
import ink.courtpace.index.core.model.PaceCategory
import ink.courtpace.index.core.model.SpeedBand
import ink.courtpace.index.core.model.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceAnalyticsTest {

    private val data: PaceData = PaceData.parse(Datasets.raw())

    @Test
    fun `pace categories follow the ITF bands`() {
        assertEquals(PaceCategory.SLOW, PaceAnalytics.category(18.9))
        assertEquals(PaceCategory.SLOW, PaceAnalytics.category(29.0))
        assertEquals(PaceCategory.MEDIUM_SLOW, PaceAnalytics.category(29.1))
        assertEquals(PaceCategory.MEDIUM_SLOW, PaceAnalytics.category(34.0))
        assertEquals(PaceCategory.MEDIUM, PaceAnalytics.category(34.1))
        assertEquals(PaceCategory.MEDIUM, PaceAnalytics.category(39.0))
        assertEquals(PaceCategory.MEDIUM_FAST, PaceAnalytics.category(39.1))
        assertEquals(PaceCategory.MEDIUM_FAST, PaceAnalytics.category(44.0))
        assertEquals(PaceCategory.FAST, PaceAnalytics.category(44.1))
    }

    @Test
    fun `speed bands straddle the tour average`() {
        assertEquals(SpeedBand.VERY_SLOW, PaceAnalytics.speedBand(0.44))
        assertEquals(SpeedBand.SLOW, PaceAnalytics.speedBand(0.80))
        assertEquals(SpeedBand.MEDIUM, PaceAnalytics.speedBand(1.00))
        assertEquals(SpeedBand.FAST, PaceAnalytics.speedBand(1.20))
        assertEquals(SpeedBand.VERY_FAST, PaceAnalytics.speedBand(1.49))
    }

    @Test
    fun `ranking is descending with sequential ranks`() {
        val ranking = PaceAnalytics.ranking(data, 2026, Metric.CPI)
        assertTrue(ranking.size >= 5)
        ranking.zipWithNext { a, b -> assertTrue("${a.value} should exceed ${b.value}", a.value >= b.value) }
        ranking.forEachIndexed { index, row -> assertEquals(index + 1, row.rank) }
    }

    @Test
    fun `ranking respects the surface filter`() {
        val clay = PaceAnalytics.ranking(data, 2026, Metric.CPI, surfaces = setOf(Surface.CLAY))
        assertTrue(clay.isNotEmpty())
        assertTrue(clay.all { it.tournament.surface == Surface.CLAY })
        val hard = PaceAnalytics.ranking(data, 2026, Metric.CPI, surfaces = setOf(Surface.HARD))
        assertTrue(hard.all { it.tournament.surface == Surface.HARD })
    }

    @Test
    fun `delta compares against the previous season with data`() {
        // Court Speed's refreshed source lists Cincinnati at 40.4 in 2026 and
        // 43.7 in 2025; the missing 2020 season is not used as the comparison.
        val ranking = PaceAnalytics.ranking(data, 2026, Metric.CPI)
        val cincinnati = ranking.first { it.tournament.id == "cincinnati" }
        assertEquals(40.4, cincinnati.value, 1e-9)
        assertEquals(43.7, cincinnati.previousValue!!, 1e-9)
        assertEquals(-3.3, cincinnati.delta!!, 1e-9)
        assertTrue(PaceAnalytics.isMeaningful(cincinnati.delta!!, Metric.CPI))
    }

    @Test
    fun `speed deltas do not mix calibrated and Tennis Abstract methods`() {
        val tournament = data.tournaments.first { it.id == "montpellier" }
        val sample = PaceData(
            data.dataset.copy(
                tournaments = listOf(tournament),
                readings = listOf(
                    PaceReading(t = "montpellier", s = 2024, speed = 0.82, src = "computed"),
                    PaceReading(t = "montpellier", s = 2025, speed = 1.70, src = "tennisabstract"),
                    PaceReading(
                        t = "montpellier", s = 2026, speed = 1.26,
                        publishedSpeed = 1.49, src = "computed",
                    ),
                ),
            ),
        )
        assertEquals(0.82, PaceAnalytics.previousValue(sample, "montpellier", 2026, Metric.SPEED)!!, 1e-9)
        assertEquals(2024, PaceAnalytics.previousSeason(sample, "montpellier", 2026, Metric.SPEED))
    }


    @Test
    fun `speed rankings and averages can isolate CALC from TA-only references`() {
        val calcTournament = data.tournaments[0]
        val taTournament = data.tournaments[1]
        val sample = PaceData(
            data.dataset.copy(
                tournaments = listOf(calcTournament, taTournament),
                readings = listOf(
                    PaceReading(
                        t = calcTournament.id, s = 2026, speed = 1.40,
                        publishedSpeed = 1.20, src = "computed",
                    ),
                    PaceReading(t = taTournament.id, s = 2026, speed = 1.30, src = "tennisabstract"),
                ),
            ),
        )

        val calc = PaceAnalytics.ranking(
            sample, 2026, Metric.SPEED, speedSources = setOf("computed", "live"),
        )
        val taOnly = PaceAnalytics.ranking(
            sample, 2026, Metric.SPEED, speedSources = setOf("tennisabstract"),
        )

        assertEquals(listOf(calcTournament.id), calc.map { it.tournament.id })
        assertEquals(listOf(taTournament.id), taOnly.map { it.tournament.id })
        assertEquals(1.40, PaceAnalytics.average(
            sample, 2026, Metric.SPEED, speedSources = setOf("computed", "live"),
        )!!, 1e-9)
        assertEquals(1.30, PaceAnalytics.average(
            sample, 2026, Metric.SPEED, speedSources = setOf("tennisabstract"),
        )!!, 1e-9)
    }

    @Test
    fun `three year average stays inside the window`() {
        val average = PaceAnalytics.threeYearAverage(data, "miami", 2026, Metric.CPI)
        assertNotNull(average)
        // 2024, 2025, 2026 -> 35.5, 40.7, 39.2
        assertEquals((35.5 + 40.7 + 39.2) / 3.0, average!!, 1e-9)
    }

    @Test
    fun `percentile spans the field`() {
        val fastest = PaceAnalytics.ranking(data, 2026, Metric.CPI).first()
        val slowest = PaceAnalytics.ranking(data, 2026, Metric.CPI).last()
        assertEquals(1.0, PaceAnalytics.percentile(data, fastest.tournament.id, 2026, Metric.CPI)!!, 1e-9)
        assertEquals(0.0, PaceAnalytics.percentile(data, slowest.tournament.id, 2026, Metric.CPI)!!, 1e-9)
    }

    @Test
    fun `divergence needs both metrics`() {
        val rows = PaceAnalytics.divergences(data, 2026)
        val expected = data.tournaments.count {
            data.value(it.id, 2026, Metric.CPI) != null &&
                data.value(it.id, 2026, Metric.SPEED) != null
        }
        assertEquals(expected, rows.size)
        rows.forEach {
            assertNotNull(it.cpi)
            assertNotNull(it.speed)
            assertTrue(it.gap in -1.0..1.0)
        }
        // sorted by absolute gap, largest disagreement first
        rows.zipWithNext { a, b ->
            assertTrue(kotlin.math.abs(a.gap) >= kotlin.math.abs(b.gap))
        }
    }

    @Test
    fun `divergence percentiles use only events with both metrics`() {
        val tournaments = data.tournaments.take(3)
        val first = tournaments[0]
        val second = tournaments[1]
        val cpiOnly = tournaments[2]
        val sample = PaceData(
            data.dataset.copy(
                tournaments = tournaments,
                readings = listOf(
                    PaceReading(t = first.id, s = 2026, cpi = 10.0, src = "courtspeed"),
                    PaceReading(t = second.id, s = 2026, cpi = 20.0, src = "courtspeed"),
                    PaceReading(t = cpiOnly.id, s = 2026, cpi = 100.0, src = "courtspeed"),
                    PaceReading(t = first.id, s = 2026, speed = 0.50, src = "computed"),
                    PaceReading(t = second.id, s = 2026, speed = 1.50, src = "computed"),
                ),
            ),
        )

        val rows = PaceAnalytics.divergences(sample, 2026)

        assertEquals(2, rows.size)
        val low = rows.first { it.tournament.id == first.id }
        val high = rows.first { it.tournament.id == second.id }
        assertEquals(0.0, low.cpiPercentile, 1e-9)
        assertEquals(0.0, low.speedPercentile, 1e-9)
        assertEquals(1.0, high.cpiPercentile, 1e-9)
        assertEquals(1.0, high.speedPercentile, 1e-9)
        assertEquals(0.0, low.gap, 1e-9)
        assertEquals(0.0, high.gap, 1e-9)
    }
    @Test
    fun `divergence sign follows the two percentiles`() {
        val rows = PaceAnalytics.divergences(data, 2026)
        rows.forEach { assertEquals(it.cpiPercentile - it.speedPercentile, it.gap, 1e-9) }
        // Source snapshots are refreshed, so event rankings can change. Validate the
        // extrema and direction labels against the current intersection instead of pinning
        // a particular event to the top or bottom.
        val highestCpi = rows.maxBy { it.cpiPercentile }
        val highestSpeed = rows.maxBy { it.speedPercentile }
        assertEquals(1.0, highestCpi.cpiPercentile, 1e-9)
        assertEquals(1.0, highestSpeed.speedPercentile, 1e-9)
        rows.forEach {
            assertEquals(
                if (it.gap > 0.0) "CPI" else "ace rate",
                it.fasterBy,
            )
        }
    }

    @Test
    fun `agreement threshold is symmetric and centred`() {
        val sample = PaceAnalytics.divergences(data, 2026).first()
        assertTrue(sample.copy(gap = 0.10).agrees)
        assertTrue(sample.copy(gap = -0.10).agrees)
        assertFalse(sample.copy(gap = 0.60).agrees)
        assertFalse(sample.copy(gap = -0.60).agrees)
        assertEquals("CPI", sample.copy(gap = 0.60).fasterBy)
        assertEquals("ace rate", sample.copy(gap = -0.60).fasterBy)
    }

    @Test
    fun `season trend is ordered and within range`() {
        val trend = PaceAnalytics.seasonTrend(data, Metric.CPI)
        assertTrue(trend.size >= 10)
        trend.zipWithNext { a, b -> assertTrue(a.first < b.first) }
        trend.forEach { (_, value) -> assertTrue(value in 15.0..60.0) }
    }

    @Test
    fun `surface averages are consistent with the ranking`() {
        val clayAvg = PaceAnalytics.average(data, 2026, Metric.CPI, Surface.CLAY)!!
        val clayRows = PaceAnalytics.ranking(data, 2026, Metric.CPI, surfaces = setOf(Surface.CLAY))
        assertEquals(clayRows.map { it.value }.average(), clayAvg, 1e-9)
        assertTrue("clay should be slower than hard", clayAvg < PaceAnalytics.average(data, 2026, Metric.CPI, Surface.HARD)!!)
    }

    @Test
    fun `spread needs more than one reading`() {
        assertTrue((PaceAnalytics.spread(data, 2026, Metric.CPI) ?: 0.0) > 0.0)

        val lonely = PaceData(
            PaceDataset(
                meta = DatasetMeta(generated = "2026-11-04", season = 2026, sources = emptyList()),
                tournaments = listOf(data.tournaments.first()),
                readings = listOf(
                    PaceReading(t = data.tournaments.first().id, s = 2026, cpi = 30.0, src = "courtspeed"),
                ),
            ),
        )
        assertEquals(null, PaceAnalytics.spread(lonely, 2026, Metric.CPI))
    }

    @Test
    fun `nearest season clamps to the seasons that hold data`() {
        val seasons = listOf(2016, 2017, 2019, 2026)
        assertEquals(2017, PaceAnalytics.nearestSeason(seasons, 2017))
        // 2018 does not exist: 2017 and 2019 are both one year away, the earlier wins.
        assertEquals(2017, PaceAnalytics.nearestSeason(seasons, 2018))
        assertEquals(2016, PaceAnalytics.nearestSeason(seasons, 1990))
        assertEquals(2026, PaceAnalytics.nearestSeason(seasons, 2040))
        assertEquals(2016, PaceAnalytics.nearestSeason(listOf(2016), 2020))
        // An empty list cannot clamp, so the request passes through untouched.
        assertEquals(2020, PaceAnalytics.nearestSeason(emptyList(), 2020))
    }

    @Test
    fun `every season in the dataset can be reached by the stepper`() {
        // The stepper walks a filtered list, so a season offered by the board must exist in
        // that list for both metrics.
        listOf(Metric.CPI, Metric.SPEED).forEach { metric ->
            val available = data.seasons.filter { data.entries(it, metric).isNotEmpty() }
            assertTrue(available.isNotEmpty())
            available.forEach { season ->
                assertEquals(season, PaceAnalytics.nearestSeason(available, season))
            }
        }
    }

    @Test
    fun `formatting matches the metric scale`() {
        assertEquals("39.2", PaceAnalytics.format(39.24, Metric.CPI))
        assertEquals("1.19", PaceAnalytics.format(1.194, Metric.SPEED))
        assertEquals("Med-slow", PaceAnalytics.label(33.0, Metric.CPI))
        assertEquals("Fast", PaceAnalytics.label(1.20, Metric.SPEED))
    }
}
