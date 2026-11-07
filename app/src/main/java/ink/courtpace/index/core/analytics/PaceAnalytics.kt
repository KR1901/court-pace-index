package ink.courtpace.index.core.analytics

import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.Level
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.PaceCategory
import ink.courtpace.index.core.model.SpeedBand
import ink.courtpace.index.core.model.Surface
import ink.courtpace.index.core.model.Tournament
import kotlin.math.abs
import kotlin.math.sqrt

data class Ranked(
    val rank: Int,
    val tournament: Tournament,
    val value: Double,
    val delta: Double?,
    val previousValue: Double?,
)

data class Divergence(
    val tournament: Tournament,
    val cpi: Double,
    val speed: Double,
    val cpiPercentile: Double,
    val speedPercentile: Double,
    /** Positive means CPI places the court faster than the ace-based rating does. */
    val gap: Double,
) {
    val agrees: Boolean get() = abs(gap) < 0.35
    val fasterBy: String get() = if (gap > 0) "CPI" else "ace rate"
}

/**
 * Everything the screens need to know about court pace, computed from the bundled data.
 * Pure functions: no Android types, so the whole thing is unit tested on the JVM.
 */
object PaceAnalytics {

    fun category(cpi: Double): PaceCategory = PaceCategory.of(cpi)

    fun speedBand(speed: Double): SpeedBand = SpeedBand.of(speed)

    fun label(value: Double, metric: Metric): String = when (metric) {
        Metric.CPI -> category(value).short
        Metric.SPEED -> speedBand(value).short
    }

    fun format(value: Double, metric: Metric): String = when (metric) {
        Metric.CPI -> String.format(java.util.Locale.US, "%.1f", value)
        Metric.SPEED -> String.format(java.util.Locale.US, "%.2f", value)
    }

    /** Ranked table for a season, optionally filtered by surface and level. */
    fun ranking(
        data: PaceData,
        season: Int,
        metric: Metric,
        surfaces: Set<Surface> = emptySet(),
        levels: Set<Level> = emptySet(),
        speedSources: Set<String>? = null,
    ): List<Ranked> {
        val rows = data.entries(season, metric)
            .filter { surfaces.isEmpty() || it.first.surface in surfaces }
            .filter { levels.isEmpty() || it.first.level in levels }
            .filter {
                speedSources == null || data.speedSource(it.first.id, season) in speedSources
            }
            .sortedByDescending { it.second }

        return rows.mapIndexed { index, (tournament, value) ->
            val previous = previousValue(data, tournament.id, season, metric)
            Ranked(
                rank = index + 1,
                tournament = tournament,
                value = value,
                delta = previous?.let { value - it },
                previousValue = previous,
            )
        }
    }

    /** Value from the most recent earlier season with a like-for-like source. */
    fun previousValue(data: PaceData, id: String, season: Int, metric: Metric): Double? =
        comparableEarlierSeasons(data, id, season, metric).firstNotNullOfOrNull { (_, value) -> value }

    fun previousSeason(data: PaceData, id: String, season: Int, metric: Metric): Int? =
        comparableEarlierSeasons(data, id, season, metric).firstOrNull()?.first

    /** Simple mean of like-for-like readings inside the last three seasons. */
    fun threeYearAverage(data: PaceData, id: String, season: Int, metric: Metric): Double? {
        val source = data.speedSource(id, season)
        val values = (season - 2..season).mapNotNull { year ->
            comparableValue(data, id, year, source, metric)
        }
        return if (values.isEmpty()) null else values.average()
    }

    private fun comparableEarlierSeasons(
        data: PaceData,
        id: String,
        season: Int,
        metric: Metric,
    ): List<Pair<Int, Double>> {
        val source = data.speedSource(id, season)
        return data.readingsFor(id).asSequence()
            .map { it.s }
            .filter { it < season }
            .distinct()
            .sortedDescending()
            .mapNotNull { year -> comparableValue(data, id, year, source, metric)?.let { year to it } }
            .toList()
    }

    private fun comparableValue(
        data: PaceData,
        id: String,
        year: Int,
        currentSource: String?,
        metric: Metric,
    ): Double? = when (metric) {
        Metric.CPI -> data.value(id, year, metric)
        Metric.SPEED -> when (currentSource) {
            "tennisabstract" -> data.publishedSpeed(id, year)
            "computed", "live" -> data.value(id, year, metric)
                ?.takeIf { data.speedSource(id, year) in setOf("computed", "live") }
            else -> null
        }
    }

    fun average(
        data: PaceData,
        season: Int,
        metric: Metric,
        surface: Surface? = null,
        speedSources: Set<String>? = null,
        surfaces: Set<Surface> = emptySet(),
    ): Double? {
        val values = data.entries(season, metric)
            .filter { surface == null || it.first.surface == surface }
            .filter { surfaces.isEmpty() || it.first.surface in surfaces }
            .filter {
                speedSources == null || data.speedSource(it.first.id, season) in speedSources
            }
            .map { it.second }
        return if (values.isEmpty()) null else values.average()
    }

    fun count(data: PaceData, season: Int, metric: Metric, surface: Surface? = null): Int =
        data.entries(season, metric).count { surface == null || it.first.surface == surface }

    /** Where a value sits in its season's field, 0..1. */
    fun percentile(data: PaceData, id: String, season: Int, metric: Metric): Double? {
        val value = data.value(id, season, metric) ?: return null
        val field = data.entries(season, metric).map { it.second }
        if (field.size < 2) return null
        val below = field.count { it < value }
        return below.toDouble() / (field.size - 1).toDouble()
    }

    /**
     * The two metrics measure different things — a physical bounce reading versus how many
     * aces the field actually hit — so where both exist the app says how far apart they are.
     * Percentiles are used because the scales are not comparable.
     */
    fun divergences(data: PaceData, season: Int): List<Divergence> =
        data.tournaments.mapNotNull { tournament ->
            val cpi = data.value(tournament.id, season, Metric.CPI) ?: return@mapNotNull null
            val speed = data.value(tournament.id, season, Metric.SPEED) ?: return@mapNotNull null
            val cpiPct = percentile(data, tournament.id, season, Metric.CPI) ?: return@mapNotNull null
            val speedPct = percentile(data, tournament.id, season, Metric.SPEED) ?: return@mapNotNull null
            Divergence(
                tournament = tournament,
                cpi = cpi,
                speed = speed,
                cpiPercentile = cpiPct,
                speedPercentile = speedPct,
                gap = cpiPct - speedPct,
            )
        }.sortedByDescending { abs(it.gap) }

    /** Mean CPI per season, for the tour-wide trend line. */
    fun seasonTrend(data: PaceData, metric: Metric, surface: Surface? = null): List<Pair<Int, Double>> =
        data.seasons.mapNotNull { season ->
            average(data, season, metric, surface)?.let { season to it }
        }

    /** Standard deviation of a season's field, used to say whether a move is noise. */
    fun spread(data: PaceData, season: Int, metric: Metric): Double? {
        val values = data.entries(season, metric).map { it.second }
        if (values.size < 2) return null
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    /**
     * Whether a season-on-season move is bigger than the noise in the field. The ITF quotes
     * ±2 points of uncertainty on its own reference method, so small moves mean little.
     */
    fun isMeaningful(delta: Double, metric: Metric): Boolean = when (metric) {
        Metric.CPI -> abs(delta) >= 2.0
        Metric.SPEED -> abs(delta) >= 0.05
    }

    /**
     * Clamps a requested season to the nearest one that actually holds readings. Switching
     * metric changes which seasons exist, and a stepper pointed at a season with no data
     * would step from an undefined position.
     */
    fun nearestSeason(available: List<Int>, requested: Int): Int {
        if (available.isEmpty()) return requested
        if (requested in available) return requested
        return available.minByOrNull { abs(it - requested) } ?: available.first()
    }

    fun coverage(data: PaceData, metric: Metric): String {
        val count = data.dataset.readings.count { it.value(metric) != null }
        val events = data.tournaments.count { t -> data.readingsFor(t.id).any { it.value(metric) != null } }
        val seasons = data.readingsForSeasons(metric)
        return when {
            seasons.isEmpty() -> "No readings"
            else -> "$count readings · $events events · ${seasons.first()}–${seasons.last()}"
        }
    }
}

private fun PaceData.readingsForSeasons(metric: Metric): List<Int> =
    dataset.readings.filter { it.value(metric) != null }.map { it.s }.distinct().sorted()
