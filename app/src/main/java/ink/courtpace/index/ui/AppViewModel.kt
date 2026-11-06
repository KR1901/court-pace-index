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
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.Surface
import ink.courtpace.index.data.BundledDataset
import ink.courtpace.index.data.DatasetUpdater
import ink.courtpace.index.data.RefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    /** Per-event ace ratings; the board must never show one tournament's rating on another. */
    var liveAceByEvent by mutableStateOf<Map<String, ink.courtpace.index.core.live.AceRating.Result>>(emptyMap())
        private set

    /** Retained for the live-row progress/diagnostic text. */
    var livePaceNote by mutableStateOf<String?>(null)
    var liveAceProgress by mutableStateOf<String?>(null)

    private val atpClient = ink.courtpace.index.data.AtpLiveClient(application)
    private val ratingStore = ink.courtpace.index.data.LiveRatingStore(application)
    private val liveRefreshMutex = Mutex()
    private var livePollingStarted = false

    init {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { BundledDataset.load(application) } }
                .onSuccess { bundled ->
                    val cached = runCatching { updater.cached() }.getOrNull()
                    publish(PaceData(DatasetPolicy.choose(bundled.dataset, cached?.dataset)))
                    showCachedLiveRatings()
                    refreshLivePace()
                    startLivePolling()
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

    /** Checks the project's published copy for a newer historical dataset. */
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

    /** Writes one live figure into the shared dataset so every screen sees the same value. */
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
        publish(PaceData(current.dataset.copy(readings = withoutOld + reading)), keepSeason = true)
    }

    /** Restore all available per-event ratings before the current scores page is checked. */
    private fun showCachedLiveRatings() {
        val events = _data.value?.dataset?.live.orEmpty()
        events.forEach { event ->
            val cached = ratingStore.read(event.t) ?: return@forEach
            applyLiveRating(event.t, cached.season, cached.speed, cached.matches, cached.aceRate)
        }
    }

    /**
     * Re-discovers the live ATP 1000/500/250 slate and refreshes each event's completed matches.
     * A mutex prevents the five-minute poll and a manual refresh from opening overlapping
     * batches of WebViews.
     */
    private suspend fun refreshLivePace() {
        if (!liveRefreshMutex.tryLock()) return

        fun status(text: String) {
            liveAceProgress = text
            livePaceNote = text
        }

        try {
            status("checking ATP live events")
            val initial = _data.value ?: return status("dataset not loaded")
            val year = java.time.LocalDate.now().year
            val discovery = atpClient.discoverEvents(
                tournaments = initial.dataset.tournaments,
                fallback = initial.dataset.live,
                year = year,
            )
            val refs = discovery.events
            if (refs.isEmpty()) {
                return status(
                    "no active ATP 1000/500/250 links · ${discovery.pageBytes}B current page",
                )
            }

            val existing = _data.value?.dataset?.live.orEmpty()
            val refreshedEvents = refs.map { ref ->
                val previous = existing.firstOrNull { it.t == ref.tournamentId && it.s == ref.year }
                previous?.copy(
                    status = "in_progress",
                    atpId = ref.atpId,
                    slug = ref.slug,
                ) ?: LiveEvent(
                    t = ref.tournamentId,
                    s = ref.year,
                    status = "in_progress",
                    atpId = ref.atpId,
                    slug = ref.slug,
                )
            }
            val latest = _data.value ?: initial
            publish(
                PaceData(latest.dataset.copy(live = refreshedEvents)),
                keepSeason = true,
            )
            liveAceByEvent = emptyMap()

            // Restore cached figures for the events still active; they remain visible while the
            // fresh match pages render, and each row reads only its own cached value.
            refs.forEach { ref ->
                val cached = ratingStore.read(ref.tournamentId)
                if (cached != null && cached.season == ref.year) {
                    applyLiveRating(
                        ref.tournamentId,
                        cached.season,
                        cached.speed,
                        cached.matches,
                        cached.aceRate,
                    )
                }
            }

            for (ref in refs) {
                val name = _data.value?.tournament(ref.tournamentId)?.name ?: ref.slug
                status("$name · reading completed singles links")
                val result = withTimeoutOrNull(WALK_TIMEOUT_MS) {
                    var discoveryLine = "discovering"
                    atpClient.fetchAceRating(
                        event = ref,
                        onProgress = { found, tried ->
                            status(
                                "$name · $discoveryLine · " +
                                    if (found > 0) "$found matches from $tried pages"
                                    else "page $tried",
                            )
                        },
                        onDiscovery = { ids, bytes ->
                            discoveryLine = if (ids > 0) {
                                "$ids completed singles links"
                            } else {
                                "no links, $bytes B page"
                            }
                            status("$name · $discoveryLine")
                        },
                    )
                } ?: Result.failure(
                    IllegalStateException("timed out reading completed match stats"),
                )

                result.onSuccess { rating ->
                    liveAceByEvent = liveAceByEvent + (ref.tournamentId to rating)
                    applyLiveRating(
                        ref.tournamentId,
                        ref.year,
                        rating.rating,
                        rating.matches,
                        rating.aceRate,
                    )
                    ratingStore.write(
                        ink.courtpace.index.core.live.CachedLiveRating(
                            tournamentId = ref.tournamentId,
                            season = ref.year,
                            speed = rating.rating,
                            matches = rating.matches,
                            aceRate = rating.aceRate,
                            asOf = java.time.Instant.now().toString(),
                        ),
                    )
                    status(
                        "$name · live speed ${"%.2f".format(rating.rating)} " +
                            "from ${rating.matches} completed matches",
                    )
                }.onFailure { error ->
                    status("$name · ${error.message ?: "match stats unavailable"}")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            status("live update failed: ${error.message}")
        } finally {
            liveRefreshMutex.unlock()
        }
    }

    /** Recheck the live slate every five minutes while the app's ViewModel is alive. */
    private fun startLivePolling() {
        if (livePollingStarted) return
        livePollingStarted = true
        viewModelScope.launch {
            while (isActive) {
                delay(LIVE_REFRESH_INTERVAL_MS)
                refreshLivePace()
            }
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
        _data.value?.let { data ->
            season = PaceAnalytics.nearestSeason(seasonsWithData(data, value), season)
        }
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
        /** Bound each event walk while retaining partial results. */
        const val WALK_TIMEOUT_MS = 900_000L

        /** Keep the event slate and ace-based reading current while the app is open. */
        const val LIVE_REFRESH_INTERVAL_MS = 5 * 60 * 1_000L

        const val MAX_COMPARE = 3
    }
}
