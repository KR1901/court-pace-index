package ink.courtpace.index.core.model

import kotlinx.serialization.Serializable

/**
 * Court surface families recognised by the ITF surface classification programme.
 * The coefficient-of-friction ranges are advisory values taken from the ITF
 * technical booklet and from Cross (2002) "Measurements of the horizontal and
 * vertical speeds of tennis courts". They are used only as a fallback when the
 * on-device slide test is skipped, and to sanity-check a measured value.
 */
@Serializable
enum class SurfaceType(
    val label: String,
    val shortLabel: String,
    val cofLow: Double,
    val cofHigh: Double,
    val cprLow: Int,
    val cprHigh: Int,
    val note: String,
) {
    ACRYLIC_HARD(
        label = "Acrylic hard court",
        shortLabel = "Acrylic",
        cofLow = 0.60, cofHigh = 0.70, cprLow = 30, cprHigh = 39,
        note = "Most acrylic-coated systems; ITF Category 2-3.",
    ),
    CLAY(
        label = "Clay / unbound mineral",
        shortLabel = "Clay",
        cofLow = 0.71, cofHigh = 0.85, cprLow = 20, cprHigh = 29,
        note = "High friction and a high bounce; ITF Category 1.",
    ),
    SYNTHETIC_CLAY(
        label = "Synthetic clay",
        shortLabel = "Synth. clay",
        cofLow = 0.66, cofHigh = 0.76, cprLow = 27, cprHigh = 35,
        note = "Sand-dressed carpet systems; ITF Category 2.",
    ),
    NATURAL_GRASS(
        label = "Natural grass",
        shortLabel = "Grass",
        cofLow = 0.50, cofHigh = 0.60, cprLow = 40, cprHigh = 50,
        note = "Low friction and a low bounce; ITF Category 4-5.",
    ),
    ARTIFICIAL_GRASS(
        label = "Artificial grass",
        shortLabel = "Art. grass",
        cofLow = 0.52, cofHigh = 0.62, cprLow = 38, cprHigh = 48,
        note = "Sand-filled or unfilled turf; ITF Category 4-5.",
    ),
    CARPET(
        label = "Carpet / indoor textile",
        shortLabel = "Carpet",
        cofLow = 0.55, cofHigh = 0.68, cprLow = 35, cprHigh = 48,
        note = "Indoor textile systems; ITF Category 3-5.",
    ),
    ASPHALT(
        label = "Asphalt / macadam",
        shortLabel = "Asphalt",
        cofLow = 0.58, cofHigh = 0.70, cprLow = 34, cprHigh = 44,
        note = "Painted or unpainted bituminous surface; ITF Category 3-4.",
    ),
    WOOD(
        label = "Wood / indoor board",
        shortLabel = "Wood",
        cofLow = 0.55, cofHigh = 0.65, cprLow = 38, cprHigh = 48,
        note = "Indoor board systems; ITF Category 4.",
    ),
    OTHER(
        label = "Other / unsure",
        shortLabel = "Other",
        cofLow = 0.58, cofHigh = 0.72, cprLow = 30, cprHigh = 42,
        note = "Measured value only; no surface family assumed.",
    ),
    ;

    val fallbackCof: Double get() = (cofLow + cofHigh) / 2.0

    val typicalCpr: Int get() = (cprLow + cprHigh) / 2

    companion object {
        fun fromName(name: String?): SurfaceType =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** ITF ball types (ITF Rules of Tennis, Appendix I). */
@Serializable
enum class BallType(
    val label: String,
    val detail: String,
) {
    TYPE_1("Type 1 - fast", "Larger, harder ball; rebounds 138-151 cm."),
    TYPE_2("Type 2 - medium", "Standard ball; rebounds 135-147 cm."),
    TYPE_3("Type 3 - slow", "Slower, larger ball; rebounds 122-135 cm."),
    HIGH_ALTITUDE("High altitude", "For play above 1219 m; rebounds 122-135 cm."),
    UNKNOWN("Not sure", "Assumed Type 2."),
    ;

    companion object {
        fun fromName(name: String?): BallType = entries.firstOrNull { it.name == name } ?: TYPE_2
    }
}

/** ITF Court Pace Rating categories (ITF CS 01/02, Table 2). */
@Serializable
enum class PaceCategory(
    val label: String,
    val categoryNumber: Int,
    val lower: Double,
    val upper: Double,
    val blurb: String,
) {
    SLOW("Slow", 1, 0.0, 29.0, "Long rallies, high bounce, ball sits up."),
    MEDIUM_SLOW("Medium-slow", 2, 30.0, 34.0, "Balanced pace; most acrylic systems."),
    MEDIUM("Medium", 3, 35.0, 39.0, "Neutral pace; all-court tennis."),
    MEDIUM_FAST("Medium-fast", 4, 40.0, 44.0, "Lower bounce, shorter rallies."),
    FAST("Fast", 5, 45.0, 100.0, "Quick through the court; serve dominant."),
    ;

    val displayName: String get() = "Category $categoryNumber - $label"

    companion object {
        fun of(cpr: Double): PaceCategory = when {
            cpr <= 29.0 -> SLOW
            cpr <= 34.0 -> MEDIUM_SLOW
            cpr <= 39.0 -> MEDIUM
            cpr <= 44.0 -> MEDIUM_FAST
            else -> FAST
        }
    }
}

/** ITF banding of the two underlying coefficients (ITF CS 01/02, Table 3). */
enum class CoefficientBand(val label: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High");

    companion object {
        fun cor(value: Double): CoefficientBand = when {
            value >= 0.85 -> HIGH
            value >= 0.79 -> MEDIUM
            else -> LOW
        }

        fun cof(value: Double): CoefficientBand = when {
            value >= 0.71 -> HIGH
            value >= 0.56 -> MEDIUM
            else -> LOW
        }
    }
}

/** A completed, saved court measurement. */
@Serializable
data class CourtRecord(
    val id: String,
    val name: String,
    val surface: SurfaceType = SurfaceType.ACRYLIC_HARD,
    val indoor: Boolean = false,
    val locationLabel: String = "",
    val ballType: BallType = BallType.TYPE_2,
    val ballTemperatureC: Double = 23.0,
    val dropHeightCm: Double = 254.0,
    val cor: Double,
    val corAdjusted: Double,
    val cof: Double,
    val cofMeasured: Boolean,
    val cpr: Double,
    val cpi: Double,
    val reboundHeightCm: Double,
    val impacts: Int,
    val corSpread: Double,
    val confidence: Double,
    val notes: String = "",
    val createdAt: Long,
    val protocol: String = PROTOCOL,
) {
    val category: PaceCategory get() = PaceCategory.of(cpr)

    companion object {
        const val PROTOCOL = "CPI-v1"
    }
}
