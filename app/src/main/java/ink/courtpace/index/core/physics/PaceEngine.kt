package ink.courtpace.index.core.physics

import ink.courtpace.index.core.model.PaceCategory
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phone-measurable court pace.
 *
 * The ITF reference method (ITF CS 01/02) fires a ball at 30 m/s and 16 degrees from an
 * air cannon and times it through infrared gates; that apparatus costs more than USD 60,000
 * and is not something a phone can do. What a phone *can* do is measure the two underlying
 * coefficients separately:
 *
 *  - Coefficient of restitution (COR) from the free-flight time between two bounces of a
 *    ball dropped from a known height. For a vertical drop,
 *        v_impact = sqrt(2 g h_drop),  v_rebound = e * v_impact,
 *    and the ball returns to the ground after dt = 2 v_rebound / g, so
 *        e = g * dt / (2 * sqrt(2 g h_drop)),
 *    which is identical to the textbook e = sqrt(h_rebound / h_drop).
 *
 *  - Coefficient of friction (COF) from the deceleration of the phone itself sliding across
 *    the surface: for a flat body decelerating only under friction, mu = a / g.
 *
 * Those two feed the published ITF combination:
 *        CPR = 100 (1 - mu) + 150 (0.81 - e_T),  e_T = e + 0.003 (23 - T)
 *
 * This is a predictive estimate, not an ITF classification. Values land within a few CPR
 * points of the reference method on the surfaces we have compared, and the app reports the
 * uncertainty it can actually see (spread between bounces, sample count).
 */
object PaceEngine {

    const val G = 9.80665

    const val ITF_DROP_HEIGHT_CM = 254.0
    const val QUICK_DROP_HEIGHT_CM = 127.0

    /** ITF temperature coefficient for COR, per degree Celsius. */
    const val TEMPERATURE_COEFF = 0.003
    const val REFERENCE_TEMPERATURE_C = 23.0

    /** ITF mean COR across all surface types ("b" in the CPR equation). */
    const val MEAN_COR = 0.81

    /** ITF pace perception constant ("a" in the CPR equation). */
    const val PERCEPTION_CONSTANT = 150.0

    /** Below this the ITF does not recommend the surface for tennis. */
    const val MIN_RECOMMENDED_COR = 0.70

    /** CPR -> 0..100 index mapping. 20 is the slowest plausible court, 50 the fastest. */
    private const val INDEX_FLOOR_CPR = 20.0
    private const val INDEX_CEILING_CPR = 50.0

    private const val MIN_COR = 0.40
    private const val MAX_COR = 0.99
    private const val MIN_COF = 0.30
    private const val MAX_COF = 1.00

    /** Rebound height of a ball that was airborne for [flightSeconds] between two bounces. */
    fun reboundHeightM(flightSeconds: Double): Double =
        G * flightSeconds * flightSeconds / 8.0

    /** Flight time between two bounces for a given restitution, used for tests and calibration. */
    fun flightTimeFor(cor: Double, dropHeightM: Double): Double =
        2.0 * cor * sqrt(2.0 * G * dropHeightM) / G

    /**
     * COR of the FIRST bounce, from the airborne time between impact 1 and impact 2.
     *
     * Bounce n leaves the surface at e^n * sqrt(2 g h_drop), so the flight times decay
     * geometrically: dt_n = 2 * e^n * sqrt(2 g h) / g. Only the first interval may be
     * divided by the drop-height impact speed; later intervals are handled by
     * [restitutionFromIntervalRatio].
     */
    fun restitutionFromFlight(flightSeconds: Double, dropHeightM: Double): Double {
        require(dropHeightM > 0.0) { "drop height must be positive" }
        if (flightSeconds <= 0.0) return MIN_COR
        val impactSpeed = sqrt(2.0 * G * dropHeightM)
        val reboundSpeed = G * flightSeconds / 2.0
        return (reboundSpeed / impactSpeed).coerceIn(MIN_COR, MAX_COR)
    }

    /**
     * COR of a bounce from the ratio of two successive flight times. Independent of the
     * drop height and of g, which makes it the sturdier of the two estimators.
     */
    fun restitutionFromIntervalRatio(previousFlight: Double, nextFlight: Double): Double {
        if (previousFlight <= 0.0 || nextFlight <= 0.0) return MIN_COR
        return (nextFlight / previousFlight).coerceIn(MIN_COR, MAX_COR)
    }

    /** COR from measured heights (the textbook form). */
    fun restitutionFromHeights(reboundM: Double, dropM: Double): Double {
        require(dropM > 0.0) { "drop height must be positive" }
        if (reboundM <= 0.0) return MIN_COR
        return sqrt(reboundM / dropM).coerceIn(MIN_COR, MAX_COR)
    }

    /** e_T = e + c (23 - T). Warmer balls give back less energy. */
    fun temperatureAdjustedCor(cor: Double, temperatureC: Double): Double =
        (cor + TEMPERATURE_COEFF * (REFERENCE_TEMPERATURE_C - temperatureC))
            .coerceIn(MIN_COR, MAX_COR)

    /** COF from the deceleration of a sliding body. */
    fun frictionFromDeceleration(decelerationMps2: Double): Double =
        (abs(decelerationMps2) / G).coerceIn(MIN_COF, MAX_COF)

    /** ITF Court Pace Rating from the two coefficients. */
    fun courtPaceRating(cof: Double, adjustedCor: Double): Double =
        100.0 * (1.0 - cof.coerceIn(MIN_COF, MAX_COF)) +
            PERCEPTION_CONSTANT * (MEAN_COR - adjustedCor)

    /** Normalised 0..100 pace index so the number is readable at a glance. */
    fun paceIndex(cpr: Double): Double =
        ((cpr - INDEX_FLOOR_CPR) / (INDEX_CEILING_CPR - INDEX_FLOOR_CPR) * 100.0)
            .coerceIn(0.0, 100.0)

    /**
     * Turns a series of impact timestamps into a bounce result.
     * Every consecutive pair of impacts yields one restitution estimate; the mean is
     * reported and the spread is used as the visible uncertainty.
     */
    fun bounceResult(
        impactTimesSeconds: List<Double>,
        dropHeightCm: Double = ITF_DROP_HEIGHT_CM,
    ): BounceResult {
        val dropM = dropHeightCm / 100.0
        val sorted = impactTimesSeconds.sorted()
        if (sorted.size < 2) {
            return BounceResult(
                cor = Double.NaN,
                spread = Double.NaN,
                impacts = sorted.size,
                reboundHeightCm = Double.NaN,
                intervals = emptyList(),
            )
        }
        val intervals = sorted.zipWithNext { a, b -> b - a }.filter { it > 0.02 }
        if (intervals.isEmpty()) {
            return BounceResult(Double.NaN, Double.NaN, sorted.size, Double.NaN, emptyList())
        }
        // First bounce: anchored to the known drop height. Later bounces: the ratio of
        // successive flight times, which is e for every interval after the first.
        val estimates = buildList {
            add(restitutionFromFlight(intervals.first(), dropM))
            intervals.zipWithNext { previous, next ->
                add(restitutionFromIntervalRatio(previous, next))
            }
        }
        val mean = estimates.average()
        val spread = if (estimates.size > 1) {
            sqrt(estimates.sumOf { (it - mean) * (it - mean) } / (estimates.size - 1))
        } else {
            0.0
        }
        val rebound = reboundHeightM(intervals.first())
        return BounceResult(
            cor = mean,
            spread = spread,
            impacts = sorted.size,
            reboundHeightCm = rebound * 100.0,
            intervals = intervals,
        )
    }

    /** Full computation from raw coefficients to the reported numbers. */
    fun result(
        cor: Double,
        cof: Double,
        temperatureC: Double,
        impacts: Int,
        spread: Double,
        cofMeasured: Boolean,
        reboundHeightCm: Double,
    ): PaceResult {
        val adjusted = temperatureAdjustedCor(cor, temperatureC)
        val cpr = courtPaceRating(cof, adjusted)
        return PaceResult(
            cor = cor,
            corAdjusted = adjusted,
            cof = cof,
            cpr = cpr,
            cpi = paceIndex(cpr),
            category = PaceCategory.of(cpr),
            confidence = confidence(impacts, spread, cofMeasured),
            cofMeasured = cofMeasured,
            reboundHeightCm = reboundHeightCm,
        )
    }

    /**
     * Confidence in the estimate, from what the measurement itself can see.
     * It is not an accuracy claim against ITF CS 01/02.
     */
    fun confidence(impacts: Int, spread: Double, cofMeasured: Boolean): Double {
        var score = 0.30
        score += when {
            impacts >= 4 -> 0.30
            impacts == 3 -> 0.22
            impacts == 2 -> 0.12
            else -> 0.0
        }
        score += when {
            spread.isNaN() -> 0.0
            spread <= 0.02 -> 0.20
            spread <= 0.04 -> 0.12
            spread <= 0.07 -> 0.05
            else -> 0.0
        }
        if (cofMeasured) score += 0.20
        return score.coerceIn(0.10, 0.97)
    }
}

data class BounceResult(
    val cor: Double,
    val spread: Double,
    val impacts: Int,
    val reboundHeightCm: Double,
    val intervals: List<Double>,
) {
    val isUsable: Boolean get() = !cor.isNaN() && impacts >= 2
}

data class PaceResult(
    val cor: Double,
    val corAdjusted: Double,
    val cof: Double,
    val cpr: Double,
    val cpi: Double,
    val category: PaceCategory,
    val confidence: Double,
    val cofMeasured: Boolean,
    val reboundHeightCm: Double,
)
