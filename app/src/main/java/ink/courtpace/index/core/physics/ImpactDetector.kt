package ink.courtpace.index.core.physics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds the acoustic impacts of a tennis ball hitting a hard surface.
 *
 * A bounce is a short broadband transient; the ball then leaves the surface, so the signal
 * drops back to the noise floor for tens of milliseconds. Detection therefore works on a
 * peak envelope with an adaptive threshold (noise floor + k * standard deviation) and a
 * refractory window, which is what makes it robust against a windy court or chatter from
 * the far court.
 *
 * Deliberately pure Kotlin (no Android types) so it can be unit tested on the JVM.
 */
class ImpactDetector(
    private val sampleRate: Int,
    private val windowSamples: Int = 32,
    private val thresholdSigmas: Double = 3.5,
    private val refractorySeconds: Double = 0.045,
    private val absoluteFloor: Double = 900.0,
) {

    /** Envelope hop in seconds, exposed so callers can reason about timing resolution. */
    val resolutionSeconds: Double = windowSamples.toDouble() / sampleRate

    /** Returns impact times in seconds from the start of [samples]. */
    fun detect(samples: ShortArray): List<Double> {
        if (samples.size < windowSamples * 4) return emptyList()

        val envelope = envelope(samples)
        val threshold = thresholdFor(envelope)
        val refractoryHops = (refractorySeconds / resolutionSeconds).toInt().coerceAtLeast(1)

        val peaks = ArrayList<Int>()
        var lastPeak = -refractoryHops
        for (i in 1 until envelope.size - 1) {
            val value = envelope[i]
            if (value < threshold) continue
            if (value < envelope[i - 1] || value < envelope[i + 1]) continue
            if (i - lastPeak < refractoryHops) {
                // Keep the stronger of two transients inside the refractory window.
                if (peaks.isNotEmpty() && value > envelope[peaks.last()]) {
                    peaks[peaks.size - 1] = i
                    lastPeak = i
                }
                continue
            }
            peaks.add(i)
            lastPeak = i
        }
        return peaks.map { refine(samples, it) / sampleRate.toDouble() }
    }

    /** Amplitude envelope, one value per hop. */
    fun envelope(samples: ShortArray): DoubleArray {
        val hops = samples.size / windowSamples
        val out = DoubleArray(hops)
        for (h in 0 until hops) {
            var peak = 0
            val start = h * windowSamples
            for (i in start until start + windowSamples) {
                val v = abs(samples[i].toInt())
                if (v > peak) peak = v
            }
            out[h] = peak.toDouble()
        }
        return out
    }

    /** Noise floor plus a multiple of the spread; never below [absoluteFloor]. */
    private fun thresholdFor(envelope: DoubleArray): Double {
        val mean = envelope.average()
        val variance = envelope.sumOf { (it - mean) * (it - mean) } / envelope.size
        val sigma = sqrt(variance)
        return maxOf(mean + thresholdSigmas * sigma, absoluteFloor)
    }

    /**
     * Nudges the hop index to the exact sample of the transient peak inside that hop,
     * which is worth a few tenths of a millisecond of timing accuracy.
     */
    private fun refine(samples: ShortArray, hop: Int): Int {
        val start = hop * windowSamples
        val end = minOf(start + windowSamples, samples.size)
        var best = start
        var bestValue = -1
        for (i in start until end) {
            val v = abs(samples[i].toInt())
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best
    }
}
