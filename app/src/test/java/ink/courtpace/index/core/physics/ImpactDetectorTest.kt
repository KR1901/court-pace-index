package ink.courtpace.index.core.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ImpactDetectorTest {

    private val sampleRate = 44_100

    @Test
    fun `detects two clean impacts within a millisecond`() {
        val samples = synthImpacts(
            sampleRate = sampleRate,
            impactsSeconds = listOf(0.75, 1.41),
            noise = 60,
        )
        val detector = ImpactDetector(sampleRate)
        val times = detector.detect(samples)
        assertEquals(2, times.size)
        assertEquals(0.75, times[0], 0.001)
        assertEquals(1.41, times[1], 0.001)
    }

    @Test
    fun `detects four bounces of a decaying ball`() {
        val impacts = listOf(0.40, 0.98, 1.41, 1.74)
        val samples = synthImpacts(sampleRate, impacts, noise = 90)
        val times = ImpactDetector(sampleRate).detect(samples)
        assertEquals(impacts.size, times.size)
        impacts.forEachIndexed { index, expected ->
            assertEquals(expected, times[index], 0.002)
        }
    }

    @Test
    fun `silence produces no impacts`() {
        val samples = ShortArray(sampleRate) { 12 }
        assertTrue(ImpactDetector(sampleRate).detect(samples).isEmpty())
    }

    @Test
    fun `loud noise floor is not mistaken for impacts`() {
        val random = Random(7)
        // Uniform noise with no transients: peaks are frequent but never stand out.
        val samples = ShortArray(sampleRate * 2) { (random.nextInt(0, 3000) - 1500).toShort() }
        val times = ImpactDetector(sampleRate).detect(samples)
        assertTrue("expected at most a couple of false positives, got ${times.size}", times.size <= 2)
    }

    @Test
    fun `close impacts are separated by the refractory window`() {
        val samples = synthImpacts(sampleRate, listOf(1.0, 1.02), noise = 40)
        val times = ImpactDetector(sampleRate, refractorySeconds = 0.045).detect(samples)
        assertEquals(1, times.size)
    }

    private fun synthImpacts(
        sampleRate: Int,
        impactsSeconds: List<Double>,
        noise: Int,
        decay: Double = 0.012,
    ): ShortArray {
        val length = (sampleRate * (impactsSeconds.max() + 0.4)).toInt()
        val out = DoubleArray(length)
        val random = Random(11)
        for (i in out.indices) {
            out[i] = (random.nextDouble() - 0.5) * noise
        }
        impactsSeconds.forEach { at ->
            val start = (at * sampleRate).toInt()
            val span = (decay * 6 * sampleRate).toInt()
            for (k in 0 until span) {
                val index = start + k
                if (index >= length) break
                val t = k.toDouble() / sampleRate
                val envelope = 26_000.0 * Math.exp(-t / decay)
                val carrier = if (k % 2 == 0) 1.0 else -1.0
                out[index] += envelope * carrier
            }
        }
        return ShortArray(length) { out[it].toInt().coerceIn(-32768, 32767).toShort() }
    }

    @Test
    fun `envelope peaks track the transients`() {
        val samples = synthImpacts(sampleRate, listOf(0.5), noise = 20)
        val envelope = ImpactDetector(sampleRate).envelope(samples)
        val peakIndex = envelope.indices.maxByOrNull { envelope[it] } ?: 0
        val peakSeconds = peakIndex * 32.0 / sampleRate
        assertTrue(abs(peakSeconds - 0.5) < 0.01)
    }
}
