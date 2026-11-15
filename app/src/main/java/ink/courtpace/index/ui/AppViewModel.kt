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

    /** Event progress is keyed by tournament; one event cannot write into another row. */
    var liveProgressByEvent by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Overall refresh diagnostic, not attached to individual event rows. */
    var livePaceNote by mutableStateOf<String?>(null)
    var liveAceProgress by mutableStateOf<String?>(null)

    private val atpClient = ink.courtpace.index.data.AtpLiveClient(application)
    private val ratingStore = ink.courtpace.index.data.LiveRatingStore(application)
    private val liveRefreshMutex = Mutex()
    private val datasetRefreshMutex = Mutex()
    /** Failed completed-event backfills are retried less often than the five-minute live poll. */
    private val completedBackfillAttemptAt = mutableMapOf<Pair<String, Int>, Long>()
    private var livePollingStarted = false
    private var datasetPollingStarted = false

    init {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { BundledDataset.load(application) } }
                .onSuccess { bundled ->
                    val cached = runCatching { updater.cached() }.getOrNull()
                    publish(PaceData(DatasetPolicy.choose(bundled.dataset, cached?.dataset)))
                    showCachedLiveRatings()
                    startDatasetPolling()
                    viewModelScope.launch {
                        refreshPublishedDataset(reportStatus = false)
                        refreshLivePace()
                    }
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

    /** Refresh the published CPI/CALC/TA dataset, preserving the selected season. */
    private suspend fun refreshPublishedDataset(reportStatus: Boolean) {
        datasetRefreshMutex.lock()
        try {
            when (val result = updater.refresh()) {
                is RefreshResult.Success -> {
                    val current = _data.value
                    val merged = if (current == null) {
                        PaceData(result.dataset)
                    } else {
                        PaceData(DatasetPolicy.choose(current.dataset, result.dataset))
                    }
                    publish(merged, keepSeason = current != null)
                    if (reportStatus) {
                        refreshState = RefreshState.Done(
                            "Updated to the ${result.dataset.meta.generated} dataset · " +
                                "${result.dataset.readings.size} readings",
                        )
                    }
                }
                RefreshResult.Unchanged -> {
                    if (reportStatus) refreshState = RefreshState.Done("Already up to date.")
                }
                is RefreshResult.Failure -> {
                    if (reportStatus) refreshState = RefreshState.Failed(result.message)
                }
            }
        } finally {
            datasetRefreshMutex.unlock()
        }
    }

    /** Manual refresh and automatic polling share the same validated download path. */
    fun refresh() {
        if (refreshState is RefreshState.Running) return
        refreshState = RefreshState.Running
        viewModelScope.launch {
            refreshPublishedDataset(reportStatus = true)
            refreshLivePace()
        }
    }

    /** Poll the published source while the app is open; CI refreshes the source snapshots. */
    private fun startDatasetPolling() {
        if (datasetPollingStarted) return
        datasetPollingStarted = true
        viewModelScope.launch {
            while (isActive) {
                delay(DATASET_REFRESH_INTERVAL_MS)
                refreshPublishedDataset(reportStatus = false)
            }
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
            // Historical dataset ace values are stored in percentage points (e.g. 8.5),
            // while AceRating.Result exposes a fraction (e.g. 0.085). Keep the shared
            // PaceReading unit consistent so detail screens do not display 0.1% instead of 8.5%.
            ace = aceRate?.times(100.0),
            src = "live",
            live = true,
            asOf = java.time.Instant.now().toString(),
            matches = matches.takeIf { it > 0 },
        )
        publish(PaceData(current.dataset.copy(readings = withoutOld + reading)), keepSeason = true)
    }

    /** Restore cached ratings only when their adjusted-side coverage is also available. */
    private fun restoreCachedLiveRating(cached: ink.courtpace.index.core.live.CachedLiveRating) {
        val result = cached.toAceRatingResultOrNull() ?: return
        liveAceByEvent = liveAceByEvent + (cached.tournamentId to result)
        applyLiveRating(
            cached.tournamentId,
            cached.season,
            cached.speed,
            cached.matches,
            cached.aceRate,
        )
    }

    /** Restore all available per-event ratings before the current scores page is checked. */
    private fun showCachedLiveRatings() {
        val dataset = _data.value?.dataset ?: return
        val baseline = dataset.speedBaseline ?: return
        ratingStore.readAll()
            .filter {
                it.methodVersion == ink.courtpace.index.core.live.CachedLiveRating.CURRENT_METHOD_VERSION &&
                    it.season == baseline.season && it.baselineGenerated == baseline.generated
            }
            .forEach(::restoreCachedLiveRating)
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
                liveSchedule = initial.dataset.liveSchedule,
            )
            val refs = discovery.events
            liveProgressByEvent = refs.associate { it.tournamentId to "Checking ATP results" }
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
            // A live rating is a season observation, not an ephemeral event card. Retain it
            // after the event leaves the active ATP slate; only metadata stays active-only.
            publish(
                PaceData(latest.dataset.copy(live = refreshedEvents)),
                keepSeason = true,
            )
            liveAceByEvent = emptyMap()
            if (refs.isEmpty()) {
                liveProgressByEvent = emptyMap()
                val note = if (discovery.hubReadable) {
                    "current ATP hub has no active events"
                } else {
                    "no dated active fallback events"
                }
                status("$note · ${discovery.pageBytes}B current page")
            }

            // Restore cached figures for the events still active; they remain visible while the
            // fresh match pages render, and each row reads only its own cached value.
            val activeBaseline = _data.value?.dataset?.speedBaseline
            refs.forEach { ref ->
                val cached = ratingStore.read(ref.tournamentId)
                if (cached != null && cached.season == ref.year &&
                    cached.methodVersion == ink.courtpace.index.core.live.CachedLiveRating.CURRENT_METHOD_VERSION &&
                    activeBaseline != null && cached.baselineGenerated == activeBaseline.generated
                ) {
                    restoreCachedLiveRating(cached)
                }
            }

            for (ref in refs) {
                val name = _data.value?.tournament(ref.tournamentId)?.name ?: ref.slug
                fun eventStatus(text: String) {
                    liveProgressByEvent = liveProgressByEvent + (ref.tournamentId to text)
                }
                eventStatus("Reading completed singles links")
                status("$name · reading completed singles links")
                val baseline = _data.value?.dataset?.speedBaseline
                    ?.takeIf { it.season == ref.year }
                if (baseline == null) {
                    status("$name · season ${ref.year} opponent-adjustment baseline unavailable; refresh the dataset")
                    continue
                }
                val result = withTimeoutOrNull(WALK_TIMEOUT_MS) {
                    var discoveryLine = "discovering"
                    atpClient.fetchAceRating(
                        event = ref,
                        baseline = baseline,
                        onProgress = { found, tried ->
                            val detail = if (found > 0) {
                                "$found matches from $tried pages"
                            } else {
                                "page $tried"
                            }
                            eventStatus("$discoveryLine · $detail")
                            status("$name · $discoveryLine · $detail")
                        },
                        onDiscovery = { ids, bytes ->
                            discoveryLine = if (ids > 0) {
                                "$ids completed singles links"
                            } else {
                                "no links, $bytes B page"
                            }
                            eventStatus(discoveryLine)
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
                            baselineGenerated = baseline.generated,
                            discoveredMatches = rating.discoveredMatches,
                            adjustedServerSides = rating.adjustedServerSides,
                            totalServerSides = rating.totalServerSides,
                            aces = rating.aces,
                            adjustmentIssues = rating.adjustmentIssues,
                        ),
                    )
                    eventStatus("Updated · ${rating.matches} completed matches")
                    status(
                        "$name · live speed ${"%.2f".format(rating.rating)} " +
                            "from ${rating.matches} completed matches",
                    )
                }.onFailure { error ->
                    val message = error.message ?: "match stats unavailable"
                    eventStatus(message)
                    status("$name · $message")
                }
            }

            // Backfill recently completed events into the season rating table without adding
            // them to the active Live now list.
            val baselineForCompleted = _data.value?.dataset?.speedBaseline
            for (ref in discovery.completedEvents) {
                val baseline = baselineForCompleted?.takeIf { it.season == ref.year }
                if (baseline == null) {
                    status("${ref.slug} · season ${ref.year} opponent-adjustment baseline unavailable")
                    continue
                }
                val cached = ratingStore.read(ref.tournamentId)
                val cachedResult = cached?.takeIf {
                    it.season == ref.year &&
                        it.methodVersion == ink.courtpace.index.core.live.CachedLiveRating.CURRENT_METHOD_VERSION &&
                        it.baselineGenerated == baseline.generated
                }?.toAceRatingResultOrNull()
                if (cachedResult != null) {
                    if (_data.value?.reading(ref.tournamentId, ref.year)?.src != "live") {
                        restoreCachedLiveRating(cached!!)
                    }
                    continue
                }

                val key = ref.tournamentId to ref.year
                val attemptedAt = completedBackfillAttemptAt[key] ?: 0L
                if (System.currentTimeMillis() - attemptedAt < COMPLETED_BACKFILL_RETRY_MS) continue
                completedBackfillAttemptAt[key] = System.currentTimeMillis()

                val name = _data.value?.tournament(ref.tournamentId)?.name ?: ref.slug
                status("$name · backfilling completed matches")
                val result = withTimeoutOrNull(WALK_TIMEOUT_MS) {
                    atpClient.fetchAceRating(event = ref, baseline = baseline)
                } ?: Result.failure(
                    IllegalStateException("timed out reading completed match stats"),
                )
                result.onSuccess { rating ->
                    liveAceByEvent = liveAceByEvent + (ref.tournamentId to rating)
                    applyLiveRating(
                        ref.tournamentId, ref.year, rating.rating, rating.matches, rating.aceRate,
                    )
                    ratingStore.write(
                        ink.courtpace.index.core.live.CachedLiveRating(
                            tournamentId = ref.tournamentId,
                            season = ref.year,
                            speed = rating.rating,
                            matches = rating.matches,
                            aceRate = rating.aceRate,
                            asOf = java.time.Instant.now().toString(),
                            baselineGenerated = baseline.generated,
                            discoveredMatches = rating.discoveredMatches,
                            adjustedServerSides = rating.adjustedServerSides,
                            totalServerSides = rating.totalServerSides,
                            aces = rating.aces,
                            adjustmentIssues = rating.adjustmentIssues,
                        ),
                    )
                    completedBackfillAttemptAt.remove(key)
                    status("$name · completed rating saved · ${rating.matches} matches")
                }.onFailure { error ->
                    status("$name · completed backfill: ${error.message ?: "match stats unavailable"}")
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

        /** Refresh the published CPI/CALC/TA payload while the app is open. */
        const val DATASET_REFRESH_INTERVAL_MS = 60 * 60 * 1_000L

        /** Keep the event slate and ace-based reading current while the app is open. */
        const val LIVE_REFRESH_INTERVAL_MS = 5 * 60 * 1_000L

        /** Retry failed completed-event backfills at most every six hours. */
        const val COMPLETED_BACKFILL_RETRY_MS = 6 * 60 * 60 * 1_000L

        const val MAX_COMPARE = 3
    }
}
