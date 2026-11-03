package ink.courtpace.index.core.physics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A single accelerometer reading. [ax]/[ay]/[az] include gravity, in m/s^2, device axes.
 */
data class AccelSample(
    val timeSeconds: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
)

data class SlideResult(
    val cof: Double,
    val meanDeceleration: Double,
    val durationSeconds: Double,
    val samples: Int,
    val flat: Boolean,
) {
    val isUsable: Boolean get() = samples > 0 && meanDeceleration > 0.0
}

/**
 * Recovers the coefficient of friction from the phone sliding across the court.
 *
 * With the phone flat, the horizontal accelerometer reading during a steady slide is the
 * friction deceleration, so mu = a / g. The first and last fifth of the slide are discarded
 * because they contain the push impulse and the final stutter.
 *
 * Pure Kotlin so the physics can be unit tested without a device.
 */
object SlideAnalyzer {

    private const val GRAVITY_MIN = 7.5
    private const val GRAVITY_MAX = 12.0
    private const val MIN_DECELERATION = 1.0
    private const val MAX_DECELERATION = 13.0
    private const val MIN_DURATION_SECONDS = 0.12
    private const val TRIM_FRACTION = 0.2

    fun analyze(samples: List<AccelSample>): SlideResult? {
        if (samples.size < 8) return null
        val sorted = samples.sortedBy { it.timeSeconds }

        val flatMask = sorted.map { abs(it.az) in GRAVITY_MIN..GRAVITY_MAX }
        val flatFraction = flatMask.count { it } / flatMask.size.toDouble()
        val horizontal = sorted.map { sqrt(it.ax * it.ax + it.ay * it.ay) }
        val moving = sorted.indices.map { i ->
            flatMask[i] && horizontal[i] >= MIN_DECELERATION && horizontal[i] <= MAX_DECELERATION
        }

        val segment = longestRun(moving) ?: return null
        val (from, to) = segment
        val duration = sorted[to].timeSeconds - sorted[from].timeSeconds
        if (duration < MIN_DURATION_SECONDS) return null

        val span = to - from
        val trim = (span * TRIM_FRACTION).toInt()
        val trimFrom = (from + trim).coerceAtMost(to)
        val trimTo = (to - trim).coerceAtLeast(trimFrom)
        if (trimTo <= trimFrom) return null

        val steady = (trimFrom..trimTo).map { horizontal[it] }
        val mean = steady.average()
        if (mean !in MIN_DECELERATION..MAX_DECELERATION) return null

        return SlideResult(
            cof = PaceEngine.frictionFromDeceleration(mean),
            meanDeceleration = mean,
            durationSeconds = duration,
            samples = steady.size,
            flat = flatFraction >= 0.6,
        )
    }

    private fun longestRun(mask: List<Boolean>): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var start = -1
        for (i in mask.indices) {
            if (mask[i] && start < 0) start = i
            if ((!mask[i] || i == mask.size - 1) && start >= 0) {
                val end = if (mask[i]) i else i - 1
                if (best == null || (end - start) > (best.second - best.first)) {
                    best = start to end
                }
                start = -1
            }
        }
        return best
    }
}
