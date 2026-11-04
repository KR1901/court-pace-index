package ink.courtpace.index.core

import ink.courtpace.index.core.data.DatasetPolicy
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.LiveEvent
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
    fun `a dataset without a live feed still parses`() {
        val withoutLive = data.dataset.copy(live = emptyList())
        assertTrue(PaceData(withoutLive).liveWithoutRating(2026, Metric.SPEED).isEmpty())
        assertFalse(PaceData(withoutLive).isLive("paris"))
    }

    @Test
    fun `live events survive the dataset policy`() {
        assertTrue(DatasetPolicy.isAcceptable(data.dataset))
        val extra = data.dataset.copy(
            live = data.dataset.live + LiveEvent(t = "vienna", s = 2026, status = "in_progress"),
        )
        assertTrue(DatasetPolicy.isAcceptable(extra))
    }
}
