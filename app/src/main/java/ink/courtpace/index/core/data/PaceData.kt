package ink.courtpace.index.core.data

import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.PaceDataset
import ink.courtpace.index.core.model.PaceReading
import ink.courtpace.index.core.model.Surface
import ink.courtpace.index.core.model.Tournament
import kotlinx.serialization.json.Json

/**
 * An indexed view over the bundled dataset. Built once, read by every screen.
 */
class PaceData(val dataset: PaceDataset) {

    /** Events in progress, by tournament id. */
    val liveById: Map<String, ink.courtpace.index.core.model.LiveEvent> =
        dataset.live.associateBy { it.t }

    fun liveEvent(id: String): ink.courtpace.index.core.model.LiveEvent? = liveById[id]

    fun isLive(id: String): Boolean = liveById.containsKey(id)

    /** Live events for a season that do not have a rating yet, so the board can still list them. */
    /** Every live event for a season, whether or not it has a rating yet. */
    fun liveEvents(season: Int, metric: ink.courtpace.index.core.model.Metric):
        List<Pair<Tournament, ink.courtpace.index.core.model.LiveEvent>> {
        if (metric != ink.courtpace.index.core.model.Metric.SPEED) return emptyList()
        return dataset.live
            .filter { it.s == season }
            .mapNotNull { event -> tournament(event.t, season)?.let { it to event } }
            .sortedBy { it.first.name }
    }

    fun liveWithoutRating(season: Int, metric: ink.courtpace.index.core.model.Metric):
        List<Pair<Tournament, ink.courtpace.index.core.model.LiveEvent>> {
        // Live ratings are ace-based, so they belong to the speed metric only. A live event
        // has nothing to say about the physical CPI.
        if (metric != ink.courtpace.index.core.model.Metric.SPEED) return emptyList()
        return dataset.live
            // Published/computed season ratings are not a current live calculation. An active
            // event remains pending until its own ATP match-feed rating is written.
            .filter { it.s == season && liveReading(it.t, it.s) == null }
            .mapNotNull { event -> tournament(event.t, season)?.let { it to event } }
            .sortedBy { it.first.name }
    }

    val tournaments: List<Tournament> = dataset.tournaments
    val byId: Map<String, Tournament> = tournaments.associateBy { it.id }
    val seasons: List<Int> = dataset.readings.map { it.s }.distinct().sorted()
    val latestSeason: Int = dataset.meta.season

    private val byTournament: Map<String, List<PaceReading>> =
        dataset.readings.groupBy { it.t }.mapValues { (_, v) -> v.sortedBy { it.s } }

    fun readingsFor(id: String): List<PaceReading> = byTournament[id].orEmpty()

    /**
     * A tournament can hold more than one reading in a season - the physical CPI and the
     * ace-based speed come from different sources - so a lookup has to say which metric it
     * wants rather than just which season.
     */
    fun reading(id: String, season: Int, metric: Metric): PaceReading? {
        val rows = byTournament[id].orEmpty()
            .filter { it.s == season && it.value(metric) != null }
        return when (metric) {
            Metric.CPI -> rows.firstOrNull()
            Metric.SPEED -> rows.firstOrNull { it.src == "live" }
                ?: rows.firstOrNull { it.src == "computed" }
                ?: rows.firstOrNull()
        }
    }

    /** A live-only reading; historical published values must not masquerade as a live result. */
    fun liveReading(id: String, season: Int): PaceReading? =
        dataset.readings.lastOrNull { it.t == id && it.s == season && it.src == "live" }

    /** Original published Tennis Abstract value, including when retained on a CALC record. */
    fun publishedSpeed(id: String, season: Int): Double? {
        val rows = byTournament[id].orEmpty().filter { it.s == season }
        return rows.firstNotNullOfOrNull { it.publishedSpeed }
            ?: rows.firstOrNull { it.src == "tennisabstract" && it.speed != null }?.speed
    }

    /** Primary Speed source: live, computed, or tennisabstract fallback. */
    fun speedSource(id: String, season: Int): String? =
        reading(id, season, Metric.SPEED)?.src

    /** The stored reading behind a figure, so callers can say how many matches it rests on. */
    fun reading(id: String, season: Int): ink.courtpace.index.core.model.PaceReading? =
        reading(id, season, Metric.SPEED)
            ?: byTournament[id]?.firstOrNull { it.s == season }

    fun value(id: String, season: Int, metric: Metric): Double? =
        reading(id, season, metric)?.value(metric)

    /** All metrics for one season, merged into a single row for display. */
    data class SeasonRow(
        val season: Int,
        val cpi: Double?,
        val speed: Double?,
        val ace: Double?,
        /** live, computed, or tennisabstract; identifies the primary Speed value. */
        val speedSource: String? = null,
        val matches: Int? = null,
        /** Original published Tennis Abstract value, where one is available. */
        val publishedSpeed: Double? = null,
        val speedCiLow: Double? = null,
        val speedCiHigh: Double? = null,
        val adjustedServerSides: Int? = null,
        val provisionalSpeed: Double? = null,
        val provisionalCiLow: Double? = null,
        val provisionalCiHigh: Double? = null,
        val provisionalServerSides: Int? = null,
        val provisionalMatches: Int? = null,
        val calculationDiagnostics: ink.courtpace.index.core.model.CalculationDiagnostics? = null,
    )

    fun seasonRows(id: String): List<SeasonRow> =
        readingsFor(id)
            .groupBy { it.s }
            .map { (season, rows) ->
                val speedReading = rows.firstOrNull { it.src == "live" && it.speed != null }
                    ?: rows.firstOrNull { it.src == "computed" && it.speed != null }
                    ?: rows.firstOrNull { it.speed != null }
                SeasonRow(
                    season = season,
                    cpi = rows.firstNotNullOfOrNull { it.cpi },
                    speed = speedReading?.speed,
                    ace = speedReading?.ace,
                    speedSource = speedReading?.src,
                    matches = speedReading?.matches,
                    publishedSpeed = rows.firstNotNullOfOrNull { it.publishedSpeed }
                        ?: rows.firstOrNull { it.src == "tennisabstract" && it.speed != null }?.speed,
                    speedCiLow = speedReading?.speedCiLow,
                    speedCiHigh = speedReading?.speedCiHigh,
                    adjustedServerSides = speedReading?.adjustedServerSides,
                    provisionalSpeed = rows.firstNotNullOfOrNull { it.provisionalSpeed },
                    provisionalCiLow = rows.firstNotNullOfOrNull { it.provisionalCiLow },
                    provisionalCiHigh = rows.firstNotNullOfOrNull { it.provisionalCiHigh },
                    provisionalServerSides = rows.firstNotNullOfOrNull { it.provisionalServerSides },
                    provisionalMatches = rows.firstNotNullOfOrNull { it.provisionalMatches },
                    // A live overlay can lack archival diagnostics; prefer the completed CALC record.
                    calculationDiagnostics = speedReading?.calculationDiagnostics
                        ?: rows.firstNotNullOfOrNull { it.calculationDiagnostics },
                )
            }
            .sortedByDescending { it.season }

    /**
     * Pick the most useful edition to open from the tournament board.
     *
     * A board-wide season can be empty or TA-only for a particular event. Completed
     * tournaments therefore open on their newest edition with archived CALC diagnostics,
     * falling back to the newest published Speed reading only when no CALC detail exists.
     * Explicit year selections on the detail screen continue to open that exact edition.
     */
    fun detailSeason(id: String, preferredSeason: Int): Int {
        val rows = seasonRows(id)
        return rows.firstOrNull {
            it.season == preferredSeason && it.calculationDiagnostics != null
        }?.season
            ?: rows.firstOrNull { it.calculationDiagnostics != null }?.season
            ?: rows.firstOrNull { it.season == preferredSeason && it.speed != null }?.season
            ?: rows.firstOrNull { it.speed != null }?.season
            ?: rows.firstOrNull { it.season == preferredSeason }?.season
            ?: preferredSeason
    }

    /** Every tournament that has a value for [metric] in [season]. */
    fun entries(season: Int, metric: Metric): List<Pair<Tournament, Double>> =
        tournaments.mapNotNull { tournament ->
            value(tournament.id, season, metric)?.let { readingValue ->
                val edition = this.tournament(tournament.id, season) ?: tournament
                edition to readingValue
            }
        }

    /** Latest season with a reading for this tournament and metric. */
    fun latestSeason(id: String, metric: Metric): Int? =
        readingsFor(id).lastOrNull { it.value(metric) != null }?.s

    fun tournament(id: String): Tournament? = byId[id]

    /** Resolve rotating venues against the selected tournament edition, not a global default. */
    fun tournament(id: String, season: Int): Tournament? {
        val base = byId[id] ?: return null
        val venue = byTournament[id].orEmpty().firstOrNull { it.s == season } ?: return base
        if (venue.venueCity == null && venue.venueCountry == null) return base
        return base.copy(
            city = venue.venueCity ?: base.city,
            country = venue.venueCountry ?: base.country,
        )
    }

    /** Tournaments that have any CPI history, longest series first. */
    fun withHistory(metric: Metric = Metric.CPI): List<Tournament> =
        tournaments
            .filter { readingsFor(it.id).any { r -> r.value(metric) != null } }
            .sortedByDescending { t -> readingsFor(t.id).count { it.value(metric) != null } }

    fun surfacesPresent(season: Int, metric: Metric): List<Surface> =
        entries(season, metric).map { it.first.surface }.distinct().sortedBy { it.ordinal }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): PaceData = PaceData(json.decodeFromString(PaceDataset.serializer(), text))
    }
}
