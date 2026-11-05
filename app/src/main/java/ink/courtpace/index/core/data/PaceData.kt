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
            .mapNotNull { event -> byId[event.t]?.let { it to event } }
            .sortedBy { it.first.name }
    }

    fun liveWithoutRating(season: Int, metric: ink.courtpace.index.core.model.Metric):
        List<Pair<Tournament, ink.courtpace.index.core.model.LiveEvent>> {
        // Live ratings are ace-based, so they belong to the speed metric only. A live event
        // has nothing to say about the physical CPI.
        if (metric != ink.courtpace.index.core.model.Metric.SPEED) return emptyList()
        return dataset.live
            .filter { it.s == season && value(it.t, it.s, metric) == null }
            .mapNotNull { event -> byId[event.t]?.let { it to event } }
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
    fun reading(id: String, season: Int, metric: Metric): PaceReading? =
        byTournament[id]?.firstOrNull { it.s == season && it.value(metric) != null }

    /** The stored reading behind a figure, so callers can say how many matches it rests on. */
    fun reading(id: String, season: Int): ink.courtpace.index.core.model.PaceReading? =
        dataset.readings.firstOrNull { it.t == id && it.s == season }

    fun value(id: String, season: Int, metric: Metric): Double? =
        reading(id, season, metric)?.value(metric)

    /** All metrics for one season, merged into a single row for display. */
    data class SeasonRow(
        val season: Int,
        val cpi: Double?,
        val speed: Double?,
        val ace: Double?,
        /** "computed" when the speed figure was recomputed from the match archive. */
        val speedSource: String? = null,
    )

    fun seasonRows(id: String): List<SeasonRow> =
        readingsFor(id)
            .groupBy { it.s }
            .map { (season, rows) ->
                SeasonRow(
                    season = season,
                    cpi = rows.firstNotNullOfOrNull { it.cpi },
                    speed = rows.firstNotNullOfOrNull { it.speed },
                    ace = rows.firstNotNullOfOrNull { it.ace },
                    speedSource = rows.firstOrNull { it.speed != null }?.src,
                )
            }
            .sortedByDescending { it.season }

    /** Every tournament that has a value for [metric] in [season]. */
    fun entries(season: Int, metric: Metric): List<Pair<Tournament, Double>> =
        tournaments.mapNotNull { tournament ->
            value(tournament.id, season, metric)?.let { tournament to it }
        }

    /** Latest season with a reading for this tournament and metric. */
    fun latestSeason(id: String, metric: Metric): Int? =
        readingsFor(id).lastOrNull { it.value(metric) != null }?.s

    fun tournament(id: String): Tournament? = byId[id]

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
