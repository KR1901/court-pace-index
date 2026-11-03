package ink.courtpace.index.core.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideAnalyzerTest {

    @Test
    fun `steady slide yields the friction coefficient`() {
        // 4.9 m/s^2 deceleration over 0.6 s, phone flat.
        val samples = buildSlide(deceleration = 4.9, duration = 0.6)
        val result = SlideAnalyzer.analyze(samples)
        assertNotNull(result)
        requireNotNull(result)
        assertTrue(result.isUsable)
        assertEquals(4.9 / PaceEngine.G, result.cof, 0.01)
    }

    @Test
    fun `push impulse at the start is trimmed`() {
        val samples = buildSlide(deceleration = 6.0, duration = 0.5, spikeAtStart = 18.0)
        val result = SlideAnalyzer.analyze(samples)
        assertNotNull(result)
        requireNotNull(result)
        assertEquals(6.0 / PaceEngine.G, result.cof, 0.02)
    }

    @Test
    fun `phone lying still produces no slide`() {
        val still = (0 until 400).map {
            AccelSample(it * 0.005, ax = 0.05, ay = 0.02, az = 9.81)
        }
        assertNull(SlideAnalyzer.analyze(still))
    }

    @Test
    fun `phone face down on the court is still read as flat`() {
        val samples = buildSlide(deceleration = 5.0, duration = 0.4, azSign = -1.0)
        val result = SlideAnalyzer.analyze(samples)
        assertNotNull(result)
        requireNotNull(result)
        assertTrue(result.flat)
    }

    @Test
    fun `a phone being carried upright is rejected`() {
        val upright = (0 until 400).map {
            AccelSample(it * 0.005, ax = 3.0, ay = 0.4, az = 0.2)
        }
        assertNull(SlideAnalyzer.analyze(upright))
    }

    private fun buildSlide(
        deceleration: Double,
        duration: Double,
        spikeAtStart: Double? = null,
        azSign: Double = 1.0,
    ): List<AccelSample> {
        val step = 0.005
        val count = (duration / step).toInt()
        return (0 until count).map { i ->
            val t = i * step
            val spike = if (spikeAtStart != null && i < 4) spikeAtStart else 0.0
            AccelSample(
                timeSeconds = t,
                ax = deceleration + spike,
                ay = 0.1,
                az = 9.81 * azSign,
            )
        }
    }
}
