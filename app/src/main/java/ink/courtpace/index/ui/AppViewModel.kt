package ink.courtpace.index.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ink.courtpace.index.core.analytics.PaceAnalytics
import ink.courtpace.index.core.data.DatasetPolicy
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.Surface
import ink.courtpace.index.data.BundledDataset
import ink.courtpace.index.data.DatasetUpdater
import ink.courtpace.index.data.RefreshResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RefreshState {
    data object Idle : RefreshState
    data object Running : RefreshState
    data class Done(val message: String) : RefreshState
    data class Failed(val message: String) : RefreshState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val updater = DatasetUpdater(application)

    private val _data = MutableStateFlow<PaceData?>(null)
    val data: StateFlow<PaceData?> = _data.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var season by mutableIntStateOf(2026)
        private set
    var metric by mutableStateOf(Metric.CPI)
        private set
    var surfaces by mutableStateOf<Set<Surface>>(emptySet())
        private set
    var compareIds by mutableStateOf(listOf("miami", "cincinnati", "montecarlo"))
        private set
    var refreshState by mutableStateOf<RefreshState>(RefreshState.Idle)

    /** Results-derived pace for the event in progress, read from the ATP public results page. */
    var livePace by mutableStateOf<ink.courtpace.index.core.live.AtpResultsParser.LivePace?>(null)

    var livePaceNote by mutableStateOf<String?>(null)

    /** Ace-based speed for the event in progress, once enough matches have stats pages. */
    var liveAce by mutableStateOf<ink.courtpace.index.core.live.AceRating.Result?>(null)

    /** Progress while the stats pages are being rendered, e.g. "9 of 31 matches read". */
    var liveAceProgress by mutableStateOf<String?>(null)

    private val atpClient = ink.courtpace.index.data.AtpLiveClient(application)

    init {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { BundledDataset.load(application) } }
                .onSuccess { bundled ->
                    val cached = runCatching { updater.cached() }.getOrNull()
                    publish(PaceData(DatasetPolicy.choose(bundled.dataset, cached?.dataset)))
                }
                .onFailure {
                    _error.value = "The bundled dataset could not be read: ${it.message}"
                }
        }
    }

    private fun publish(parsed: PaceData, keepSeason: Boolean = false) {
        _data.value = parsed
        val available = seasonsWithData(parsed, metric)
        season = if (keepSeason) {
            PaceAnalytics.nearestSeason(available, season)
        } else {
            available.lastOrNull() ?: parsed.latestSeason
        }
    }

    private fun seasonsWithData(data: PaceData, metric: Metric): List<Int> =
        data.seasons.filter { data.entries(it, metric).isNotEmpty() }

    /** Checks the project's published copy for a newer dataset. */
    fun refresh() {
        if (refreshState is RefreshState.Running) return
        refreshState = RefreshState.Running
        viewModelScope.launch {
            when (val result = updater.refresh()) {
                is RefreshResult.Success -> {
                    val current = _data.value
                    val merged = if (current == null) {
                        PaceData(result.dataset)
                    } else {
                        PaceData(DatasetPolicy.choose(current.dataset, result.dataset))
                    }
                    publish(merged, keepSeason = true)
                    refreshState = RefreshState.Done(
                        "Updated to the ${result.dataset.meta.generated} dataset · " +
                            "${result.dataset.readings.size} readings",
                    )
                }

                RefreshResult.Unchanged -> refreshState = RefreshState.Done("Already up to date.")
                is RefreshResult.Failure -> refreshState = RefreshState.Failed(result.message)
            }
            refreshLivePace()
        }
    }

    /**
     * Writes a computed live figure into the dataset in memory, so it reaches every screen
     * through the paths that already exist rather than through new plumbing.
     */
    private fun applyLiveRating(
        tournamentId: String,
        season: Int,
        speed: Double,
        matches: Int,
        aceRate: Double?,
    ) {
        val current = _data.value ?: return
        val withoutOld = current.dataset.readings.filterNot {
            it.t == tournamentId && it.s == season && it.src == "live"
        }
        val reading = ink.courtpace.index.core.model.PaceReading(
            t = tournamentId,
            s = season,
            speed = speed,
            ace = aceRate,
            src = "live",
            live = true,
            asOf = java.time.Instant.now().toString(),
            matches = matches.takeIf { it > 0 },
        )
        publish(PaceData(current.dataset.copy(readings = withoutOld + reading)))
    }

    /**
     * Pulls the live event's results straight from the ATP page, so the reload button updates
     * it instantly rather than waiting for the scheduled job.
     */
    private suspend fun refreshLivePace() {
        val data = _data.value ?: return
        val event = data.dataset.live.firstOrNull { it.atpId != null } ?: return
        atpClient.fetch(event.atpId!!, event.slug ?: "paris")
            .onSuccess {
                livePace = it
                livePaceNote = null
                // The ace figure wins when both exist; the duration reading fills the gap.
                if (liveAce == null && it.rating != null) {
                    applyLiveRating(event.t, event.s, it.rating, it.matches ?: 0, null)
                }
            }
            .onFailure { livePaceNote = it.message ?: "could not read the ATP results page" }

        liveAceProgress = "reading ATP match stats"
        atpClient.fetchAceRating(event.atpId!!) { found, tried ->
            liveAceProgress = "$found of $tried matches read"
        }
            .onSuccess {
                liveAce = it
                liveAceProgress = null
                applyLiveRating(event.t, event.s, it.rating, it.matches, it.aceRate)
            }
            .onFailure {
                liveAce = null
                liveAceProgress = null
                livePaceNote = it.message ?: livePaceNote
            }
    }

    fun clearRefreshState() {
        refreshState = RefreshState.Idle
    }

    fun selectSeason(value: Int) {
        season = value
    }

    fun selectMetric(value: Metric) {
        metric = value
        // The two metrics do not cover the same seasons, so the selected season may not
        // exist for the new one.
        _data.value?.let { data -> season = PaceAnalytics.nearestSeason(seasonsWithData(data, value), season) }
    }

    fun toggleSurface(surface: Surface?) {
        surfaces = when {
            surface == null -> emptySet()
            surface in surfaces -> surfaces - surface
            else -> surfaces + surface
        }
    }

    fun toggleCompare(id: String) {
        compareIds = when {
            id in compareIds -> compareIds - id
            compareIds.size >= MAX_COMPARE -> compareIds.drop(1) + id
            else -> compareIds + id
        }
    }

    fun isComparing(id: String): Boolean = id in compareIds

    /** Seasons that hold at least one reading for the active metric. */
    fun seasonsFor(data: PaceData, metric: Metric): List<Int> =
        data.seasons.filter { data.entries(it, metric).isNotEmpty() }

    fun clearError() {
        _error.value = null
    }

    companion object {
        const val MAX_COMPARE = 3
    }
}
