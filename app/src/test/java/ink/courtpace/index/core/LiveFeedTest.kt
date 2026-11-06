package ink.courtpace.index.core

import ink.courtpace.index.core.data.DatasetPolicy
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.LiveSpeedBaseline
import ink.courtpace.index.core.model.LiveSpeedPlayerProfile
import ink.courtpace.index.core.model.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFeedTest {

    private val data = PaceData.parse(Datasets.raw())

    @Test
    fun `the bundled feed carries the event in progress`() {
        val paris = data.liveEvent("paris")
        assertNotNull("Paris should be listed as live", paris)
        assertEquals(2026, paris!!.s)
        assertEquals("in_progress", paris.status)
        // A live event may exist without a rating; it must not invent one.
        assertNull(data.value("paris", 2026, Metric.SPEED))
    }

    @Test
    fun `live is per event, not global`() {
        assertTrue(data.isLive("paris"))
        assertFalse(data.isLive("miami"))
        assertNull(data.liveEvent("miami"))
    }

    @Test
    fun `an event with no rating is still listed so it can be shown`() {
        val pending = data.liveWithoutRating(2026, Metric.SPEED)
        assertTrue("Paris should appear as pending", pending.any { it.first.id == "paris" })
        // Only the speed metric: a live ace rating says nothing about the physical CPI.
        assertTrue(data.liveWithoutRating(2026, Metric.CPI).none { it.first.id == "paris" })
    }

    @Test
    fun `a live event that has a rating leaves the pending list`() {
        val withRating = data.dataset.copy(
            readings = data.dataset.readings + ink.courtpace.index.core.model.PaceReading(
                t = "paris", s = 2026, speed = 1.04, ace = 11.2, src = "live",
                live = true, asOf = "2026-11-04T18:00:00Z", matches = 42,
            ),
        )
        val merged = PaceData(withRating)
        assertTrue(merged.liveWithoutRating(2026, Metric.SPEED).none { it.first.id == "paris" })
        assertEquals(1.04, merged.value("paris", 2026, Metric.SPEED)!!, 1e-9)
        assertTrue(merged.seasonRows("paris").first { it.season == 2026 }.speed != null)
    }

    @Test
    fun `a fresh live value and match count take precedence over a published season value`() {
        val withoutParisSeason = data.dataset.readings.filterNot {
            it.t == "paris" && it.s == 2026 && it.speed != null
        }
        val merged = PaceData(
            data.dataset.copy(
                readings = withoutParisSeason + listOf(
                    ink.courtpace.index.core.model.PaceReading(
                        t = "paris", s = 2026, speed = 0.91, src = "tennisabstract",
                    ),
                    ink.courtpace.index.core.model.PaceReading(
                        t = "paris", s = 2026, speed = 1.12, src = "live",
                        live = true, matches = 55,
                    ),
                ),
            ),
        )

        assertEquals(1.12, merged.value("paris", 2026, Metric.SPEED)!!, 1e-9)
        assertEquals(55, merged.reading("paris", 2026)!!.matches)
        assertEquals("live", merged.reading("paris", 2026, Metric.SPEED)!!.src)
    }

    @Test
    fun `a dataset without a live feed still parses`() {
        val withoutLive = data.dataset.copy(live = emptyList())
        assertTrue(PaceData(withoutLive).liveWithoutRating(2026, Metric.SPEED).isEmpty())
        assertFalse(PaceData(withoutLive).isLive("paris"))
    }

    @Test
    fun `an ATP id missing from a cached dataset is recovered`() {
        // A cache written by an earlier release has no atpId. The lookup returned null, the
        // fetch was skipped before any network call, and the feature did nothing at all.
        val staleLive = data.dataset.live.map { it.copy(atpId = null, slug = null) }
        val stale = data.dataset.copy(live = staleLive)
        val chosen = DatasetPolicy.choose(data.dataset, stale)
        assertTrue("the bundled definition must win", chosen.live.all { it.atpId != null })
        // And even with nothing to recover from, the registry knows where Paris lives.
        assertEquals(352, ink.courtpace.index.core.live.AtpEvents.forTournament("paris")?.atpId)
    }

    @Test
    fun `a published dataset without a live list cannot erase the event in progress`() {
        // The workflow publishes datasets built from the asset; one produced before the live
        // feed existed has no live array, and taking it whole made the live row disappear.
        val remote = data.dataset.copy(live = emptyList())
        val chosen = DatasetPolicy.choose(data.dataset, remote)
        assertTrue("live events must survive the swap", chosen.live.isNotEmpty())
        assertTrue(PaceData(chosen).isLive("paris"))
    }

    @Test
    fun `a live event stays listed once it has a rating`() {
        val withRating = data.dataset.copy(
            readings = data.dataset.readings + ink.courtpace.index.core.model.PaceReading(
                t = "paris", s = 2026, speed = 1.12, src = "live", live = true, matches = 30,
            ),
        )
        val merged = PaceData(withRating)
        // It leaves the "awaiting rating" list...
        assertTrue(merged.liveWithoutRating(2026, Metric.SPEED).none { it.first.id == "paris" })
        // ...but must still be shown as live, with its number.
        val live = merged.liveEvents(2026, Metric.SPEED)
        assertTrue(live.any { it.first.id == "paris" })
        assertEquals(1.12, merged.value("paris", 2026, Metric.SPEED)!!, 1e-9)
    }

    @Test
    fun `live events survive the dataset policy`() {
        assertTrue(DatasetPolicy.isAcceptable(data.dataset))
        val extra = data.dataset.copy(
            live = data.dataset.live + LiveEvent(t = "vienna", s = 2026, status = "in_progress"),
        )
        assertTrue(DatasetPolicy.isAcceptable(extra))
    }
    @Test
    fun `a remote dataset without the new baseline cannot erase the bundled one`() {
        val bundled = data.dataset.copy(
            speedBaseline = LiveSpeedBaseline(
                season = 2026,
                normalisation = 1.02,
                profiles = listOf(
                    LiveSpeedPlayerProfile("Player One", serveRate = 0.12, concedeRate = 0.08),
                ),
            ),
        )
        val chosen = DatasetPolicy.choose(bundled, data.dataset.copy(speedBaseline = null))
        assertEquals(bundled.speedBaseline, chosen.speedBaseline)
    }

    @Test
    fun `an invalid opponent adjustment baseline rejects the dataset`() {
        val invalid = data.dataset.copy(
            speedBaseline = LiveSpeedBaseline(
                season = 2026,
                normalisation = -1.0,
                profiles = emptyList(),
            ),
        )
        assertFalse(DatasetPolicy.isAcceptable(invalid))
    }

    @Test
    fun `an unversioned pooled live reading is not accepted as a current-method result`() {
        val remote = data.dataset.copy(
            readings = data.dataset.readings + ink.courtpace.index.core.model.PaceReading(
                t = "paris", s = 2026, speed = 1.47, ace = 11.5, src = "live",
                live = true, matches = 47,
            ),
        )
        val chosen = DatasetPolicy.choose(data.dataset, remote)
        assertNull(PaceData(chosen).liveReading("paris", 2026))
    }

}
