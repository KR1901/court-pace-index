package ink.courtpace.index.core

import ink.courtpace.index.core.data.DatasetPolicy
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.PaceDataset
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.Level
import ink.courtpace.index.core.model.Surface
import ink.courtpace.index.core.model.Tournament
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetPolicyTest {

    private val bundled: PaceDataset = PaceData.parse(Datasets.raw()).dataset

    private fun copyWith(
        readings: Int = bundled.readings.size,
        tournaments: Int = bundled.tournaments.size,
    ): PaceDataset {
        val kept = bundled.tournaments.take(tournaments).map { it.id }.toSet()
        return bundled.copy(
            tournaments = bundled.tournaments.take(tournaments),
            readings = bundled.readings.filter { it.t in kept }.take(readings),
        )
    }

    @Test
    fun `the bundled dataset is acceptable`() {
        assertTrue(DatasetPolicy.isAcceptable(bundled))
    }

    @Test
    fun `a truncated download is rejected`() {
        assertFalse(DatasetPolicy.isAcceptable(copyWith(readings = 10)))
        assertFalse(DatasetPolicy.isAcceptable(copyWith(tournaments = 3)))
    }

    @Test
    fun `readings pointing at unknown tournaments are rejected`() {
        val broken = bundled.copy(
            readings = bundled.readings + bundled.readings.first().copy(t = "not-a-tournament"),
        )
        assertFalse(DatasetPolicy.isAcceptable(broken))
    }

    @Test
    fun `impossible values are rejected`() {
        val broken = bundled.copy(
            readings = bundled.readings + bundled.readings.first().copy(s = 2026, cpi = 900.0),
        )
        assertFalse(DatasetPolicy.isAcceptable(broken))
    }

    @Test
    fun `an unusable remote copy never replaces the bundled one`() {
        assertEquals(bundled, DatasetPolicy.choose(bundled, null))
        assertEquals(bundled, DatasetPolicy.choose(bundled, copyWith(readings = 5)))
    }

    @Test
    fun `a larger valid remote copy wins`() {
        val extra = bundled.readings.first().copy(s = 1999)
        val remote = bundled.copy(readings = bundled.readings + extra)
        assertTrue(DatasetPolicy.isAcceptable(remote))
        assertEquals(remote, DatasetPolicy.choose(bundled, remote))
    }

    @Test
    fun `a remote ATP slate does not erase a bundled Davis Cup live event`() {
        val davis = Tournament(
            id = "daviscup", name = "Davis Cup", city = "Bologna", country = "ITA",
            surface = Surface.MULTI, indoor = false, level = Level.SPECIAL,
        )
        val bundledEvent = LiveEvent(
            t = "daviscup", s = 2026, status = "in_progress",
            start = "2026-11-24", end = "2026-11-29", slug = "davis-cup-2026",
        )
        val atpEvent = LiveEvent(
            t = "atpfinals", s = 2026, status = "in_progress",
            start = "2026-11-15", end = "2026-11-22",
            atpId = 605, slug = "nitto-atp-finals",
        )
        val withDavisCup = bundled.copy(
            tournaments = bundled.tournaments + davis,
            live = listOf(bundledEvent),
        )
        val remoteAtpOnly = withDavisCup.copy(live = listOf(atpEvent))

        val chosen = DatasetPolicy.choose(withDavisCup, remoteAtpOnly)

        assertEquals(setOf("daviscup", "atpfinals"), chosen.live.map { it.t }.toSet())
    }

    @Test
    fun `a smaller but valid remote copy is ignored`() {
        val smaller = bundled.copy(readings = bundled.readings.drop(20))
        assertTrue(DatasetPolicy.isAcceptable(smaller))
        assertEquals(bundled, DatasetPolicy.choose(bundled, smaller))
    }
}
