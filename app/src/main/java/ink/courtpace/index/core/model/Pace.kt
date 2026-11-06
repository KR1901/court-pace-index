package ink.courtpace.index.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Surface(val label: String) {
    HARD("Hard"),
    CLAY("Clay"),
    GRASS("Grass"),
    ;

    companion object {
        fun fromName(name: String?): Surface = entries.firstOrNull { it.name == name } ?: HARD
    }
}

@Serializable
enum class Level(val label: String, val short: String) {
    @SerialName("GS") GS("Grand Slam", "GS"),
    @SerialName("M1000") M1000("Masters 1000", "M1000"),
    @SerialName("FINALS") FINALS("ATP Finals", "Finals"),
    @SerialName("ATP500") ATP500("ATP 500", "500"),
    @SerialName("ATP250") ATP250("ATP 250", "250"),
    @SerialName("SPECIAL") SPECIAL("Special event", "Event"),
    ;

    companion object {
        fun fromName(name: String?): Level = entries.firstOrNull { it.name == name } ?: ATP250
    }
}

/** ITF / ATP pace bands used for CPI, per ITF CS 01/02 Table 2. */
enum class PaceCategory(val label: String, val short: String, val lower: Double, val upper: Double) {
    SLOW("Slow", "Slow", 0.0, 29.0),
    MEDIUM_SLOW("Medium-slow", "Med-slow", 30.0, 34.0),
    MEDIUM("Medium", "Medium", 35.0, 39.0),
    MEDIUM_FAST("Medium-fast", "Med-fast", 40.0, 44.0),
    FAST("Fast", "Fast", 45.0, 200.0),
    ;

    companion object {
        fun of(cpi: Double): PaceCategory = when {
            cpi <= 29.0 -> SLOW
            cpi <= 34.0 -> MEDIUM_SLOW
            cpi <= 39.0 -> MEDIUM
            cpi <= 44.0 -> MEDIUM_FAST
            else -> FAST
        }
    }
}

/**
 * Bands for the ace-based surface speed rating, where 1.00 is a tour-average surface and
 * the usual range across ATP events is 0.5 to 1.5. The cut points are ours, not the
 * source's: they simply slice that range evenly around the average.
 */
enum class SpeedBand(val label: String, val short: String, val lower: Double, val upper: Double) {
    VERY_SLOW("Very slow", "V.slow", 0.0, 0.75),
    SLOW("Slow", "Slow", 0.75, 0.92),
    MEDIUM("Medium", "Medium", 0.92, 1.08),
    FAST("Fast", "Fast", 1.08, 1.25),
    VERY_FAST("Very fast", "V.fast", 1.25, 3.0),
    ;

    companion object {
        fun of(speed: Double): SpeedBand = when {
            speed < 0.75 -> VERY_SLOW
            speed < 0.92 -> SLOW
            speed < 1.08 -> MEDIUM
            speed < 1.25 -> FAST
            else -> VERY_FAST
        }
    }
}

/** The two metrics the app works with. */
enum class Metric(val label: String, val short: String) {
    CPI("Court Pace Index", "CPI"),
    SPEED("Surface speed", "Speed"),
}

@Serializable
data class Tournament(
    val id: String,
    val name: String,
    val city: String,
    val country: String,
    val surface: Surface = Surface.HARD,
    val indoor: Boolean = false,
    val level: Level = Level.ATP250,
    val altitudeM: Int? = null,
) {
    val setting: String get() = if (indoor) "Indoor" else "Outdoor"
    val surfaceLabel: String get() = if (indoor) "${surface.label} · indoor" else surface.label
    val location: String get() = "$city, $country"
}

/**
 * One observation for one tournament in one season. CPI and speed come from different
 * sources and are rarely both present, so each field is optional.
 */
@Serializable
data class PaceReading(
    val t: String,
    val s: Int,
    val cpi: Double? = null,
    val speed: Double? = null,
    val ace: Double? = null,
    val date: String? = null,
    val src: String,
    /** True when the figure was computed from a tournament still in progress. */
    val live: Boolean = false,
    /** When the live figure was computed, for the "as of" line. */
    val asOf: String? = null,
    /** Matches behind a live figure. Null when unknown. */
    val matches: Int? = null,
) {
    fun value(metric: Metric): Double? = when (metric) {
        Metric.CPI -> cpi
        Metric.SPEED -> speed
    }
}

@Serializable
data class SourceInfo(
    val id: String,
    val name: String,
    val metric: String,
    val detail: String,
    val url: String,
)

@Serializable
data class DatasetMeta(
    val generated: String,
    val season: Int,
    val sources: List<SourceInfo> = emptyList(),
)

@Serializable
data class LiveSpeedPlayerProfile(
    val name: String,
    val serveRate: Double? = null,
    val concedeRate: Double? = null,
)

/** Season-wide player tendencies and normaliser for historical-method live Speed calculations. */
@Serializable
data class LiveSpeedBaseline(
    val season: Int,
    val normalisation: Double,
    val profiles: List<LiveSpeedPlayerProfile> = emptyList(),
    val generated: String = "",
    /** Season used to estimate player tendencies; can be the prior year during a cold start. */
    val profileSeason: Int = season,
)

@Serializable
data class PaceDataset(
    val meta: DatasetMeta,
    val tournaments: List<Tournament> = emptyList(),
    val readings: List<PaceReading> = emptyList(),
    /** Events in progress right now. An event can be here before it has a rating. */
    val live: List<LiveEvent> = emptyList(),
    /** Generated by the same archive pipeline as historical Speed readings. */
    val speedBaseline: LiveSpeedBaseline? = null,
)

/**
 * A tournament that is being played now. It carries status before it carries numbers: the
 * rating only exists once the pipeline has processed matches from it.
 */
@Serializable
data class LiveEvent(
    val t: String,
    val s: Int,
    val status: String = "in_progress",
    val start: String? = null,
    val end: String? = null,
    val round: String? = null,
    val matches: Int? = null,
    val asOf: String? = null,
    /** ATP's numeric id for the event, used to build its public results URL. */
    val atpId: Int? = null,
    val slug: String? = null,
)
