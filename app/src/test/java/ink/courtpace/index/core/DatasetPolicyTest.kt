package ink.courtpace.index.core

import ink.courtpace.index.core.data.DatasetPolicy
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.PaceDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetPolicyTest {

    private val bundled: PaceDataset = PaceData.parse(Datasets.raw()).dataset

    private fun copyWith(readings: Int, tournaments: Int = bundled.tournaments.size): PaceDataset {
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
    fun `a smaller but valid remote copy is ignored`() {
        val smaller = bundled.copy(readings = bundled.readings.drop(20))
        assertTrue(DatasetPolicy.isAcceptable(smaller))
        assertEquals(bundled, DatasetPolicy.choose(bundled, smaller))
    }
}
