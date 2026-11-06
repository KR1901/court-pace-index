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

    private val ratingStore = ink.courtpace.index.data.LiveRatingStore(application)


    init {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { BundledDataset.load(application) } }
                .onSuccess { bundled ->
                    val cached = runCatching { updater.cached() }.getOrNull()
                    publish(PaceData(DatasetPolicy.choose(bundled.dataset, cached?.dataset)))
                    // The last figure is shown immediately, so the row is never blank while the
                    // pages are being read again in the background.
                    showCachedLiveRating()
                    refreshLivePace()
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

    private fun showCachedLiveRating() {
        val data = _data.value ?: return
        val event = data.dataset.live.firstOrNull() ?: return
        val cached = ratingStore.read(event.t) ?: return
        applyLiveRating(event.t, cached.season, cached.speed, cached.matches, cached.aceRate)
        livePaceNote = "cached ${cached.asOf.take(16).replace('T', ' ')} UTC"
        liveAceProgress = null
    }

    private suspend fun refreshLivePace() {
        // Every step writes a status, so the row can never be silent about what happened.
        fun status(text: String) {
            liveAceProgress = text
            livePaceNote = text
        }
        try {
            status("starting")
            val data = _data.value
            if (data == null) return status("dataset not loaded")

            val event = data.dataset.live.firstOrNull() ?: return status("no live event")
            val ref = event.atpId?.let {
                ink.courtpace.index.core.live.AtpEvents.Ref(it, event.slug ?: "paris", event.s)
            } ?: ink.courtpace.index.core.live.AtpEvents.forTournament(event.t)
            ?: return status("no ATP page known for ${event.t}")

            // The ace figure is the one that matters, so it goes first and reports as it goes.
            status("reading ATP match stats")
            val aceResult = kotlinx.coroutines.withTimeoutOrNull(WALK_TIMEOUT_MS) {
                var discovery = "discovering"
                atpClient.fetchAceRating(
                    ref.atpId,
                    ref.year,
                    onProgress = { found, tried ->
                        status(
                            "$discovery · " +
                                if (found > 0) "$found matches from $tried pages" else "page $tried",
                        )
                    },
                    onDiscovery = { ids, bytes ->
                        // Kept for the whole run and prefixed onto every status. The point of the
                        // line is to tell us what the phone can see; the walk used to replace it
                        // a moment after it appeared.
                        discovery = if (ids > 0) "$ids match links" else "no links, ${bytes}B page"
                        status(discovery)
                    },
                )
            } ?: Result.failure(IllegalStateException("timed out reading the stats pages"))
            aceResult
                .onSuccess {
                    liveAce = it
                    applyLiveRating(event.t, event.s, it.rating, it.matches, it.aceRate)
                    ratingStore.write(
                        ink.courtpace.index.core.live.CachedLiveRating(
                            tournamentId = event.t,
                            season = event.s,
                            speed = it.rating,
                            matches = it.matches,
                            aceRate = it.aceRate,
                            asOf = java.time.Instant.now().toString(),
                        ),
                    )
                    status("live speed ${"%.2f".format(it.rating)} from ${it.matches} matches")
                }
                .onFailure { status("stats: ${it.message}") }

            // The results page is a single request and lists every completed match, so it is
            // always read: it supplies the figure when the ace walk falls short, and it tells us
            // how many matches the event actually has.
            run {
                status("trying the results page")
                val durationResult = kotlinx.coroutines.withTimeoutOrNull(RESULTS_TIMEOUT_MS) {
                    atpClient.fetch(ref.atpId, ref.slug)
                } ?: Result.failure<ink.courtpace.index.core.live.AtpResultsParser.LivePace>(
                    IllegalStateException("the results page did not answer"),
                )
                durationResult
                    .onSuccess {
                        livePace = it
                        // The ace figure wins when it covers most of the draw; otherwise the
                        // duration reading is the honest one to show, because it covers all.
                        val aceCoverage = liveAce?.matches ?: 0
                        val total = it.matches ?: 0
                        if (it.rating != null && aceCoverage < (total * 8) / 10) {
                            applyLiveRating(event.t, event.s, it.rating, total, null)
                        }
                    }
                    .onFailure { livePaceNote = it.message ?: livePaceNote }
            }
        } catch (error: Throwable) {
            status("live failed: ${error.message}")
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
        /** The whole walk must answer within this, however the pages behave. */
        const val WALK_TIMEOUT_MS = 900_000L

        /** The results page is small and server-rendered; it has no excuse to take long. */
        const val RESULTS_TIMEOUT_MS = 90_000L

        const val MAX_COMPARE = 3
    }
}
