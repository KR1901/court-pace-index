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
import ink.courtpace.index.data.TmlDavisCupClient
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
    private val tmlDavisCupClient = TmlDavisCupClient()
    private val ratingStore = ink.courtpace.index.data.LiveRatingStore(application)
    private val liveRefreshMutex = Mutex()
    private val datasetRefreshMutex = Mutex()
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
                    // DatasetPolicy intentionally drops volatile live rows from a downloaded
                    // snapshot; restore our locally calculated ratings, including completed ones.
                    showCachedLiveRatings()
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
        // Never restore legacy TennisRace/ATP-prototype Davis Cup values. Only the TML
        // match-stat adapter may populate this separate annual target.
        if (
            cached.tournamentId in setOf("daviscup", "daviscupfinal8") &&
            cached.source != ink.courtpace.index.core.live.CachedLiveRating.TML_DAVIS_CUP_SOURCE
        ) return
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
        val parsed = PaceData(dataset)
        ratingStore.readAll()
            .filter {
                it.methodVersion == ink.courtpace.index.core.live.CachedLiveRating.CURRENT_METHOD_VERSION &&
                    it.season == baseline.season &&
                    it.baselineGenerated == baseline.generated &&
                    parsed.isLive(it.tournamentId, it.season)
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
            val existing = _data.value?.dataset?.live.orEmpty()
            // Davis Cup is read through its separately validated TML annual + ongoing match-stat
            // feeds. Keep the official schedule row visible while those requests are in flight.
            val davisCupFinal8Event = existing.firstOrNull {
                it.t == "daviscupfinal8" && it.s == year
            }
            val davisCupFinal8Pending = "TML · checking Final 8 singles statistics"
            liveProgressByEvent = refs.associate { it.tournamentId to "Checking ATP results" } +
                if (davisCupFinal8Event != null) {
                    mapOf("daviscupfinal8" to davisCupFinal8Pending)
                } else emptyMap()
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
                PaceData(latest.dataset.copy(live = refreshedEvents + listOfNotNull(davisCupFinal8Event))),
                keepSeason = true,
            )
            liveAceByEvent = emptyMap()
            if (refs.isEmpty()) {
                liveProgressByEvent = if (davisCupFinal8Event != null) {
                    mapOf("daviscupfinal8" to davisCupFinal8Pending)
                } else emptyMap()
                val note = if (discovery.hubReadable) {
                    "current ATP hub has no active events"
                } else {
                    "no dated active fallback events"
                }
                status("$note · ${discovery.pageBytes}B current page")
            }

            // Restore valid per-event figures from the local cache while fresh pages render.
            val activeBaseline = _data.value?.dataset?.speedBaseline
            if (activeBaseline != null) {
                val activeKeys = refs.map { it.tournamentId to it.year }.toSet() +
                    if (davisCupFinal8Event != null) setOf("daviscupfinal8" to year) else emptySet()
                ratingStore.readAll()
                    .filter {
                        it.season == activeBaseline.season &&
                            (it.tournamentId to it.season) in activeKeys &&
                            it.methodVersion == ink.courtpace.index.core.live.CachedLiveRating.CURRENT_METHOD_VERSION &&
                            it.baselineGenerated == activeBaseline.generated
                    }
                    .forEach(::restoreCachedLiveRating)
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

            // The live row is only the Final 8. Earlier qualifying/group ties remain a
            // separate archived Davis Cup target and never enter ATP profiles or normalisation.
            // Both phase samples use the same existing AceRating calculation; no formula or
            // calibration change is made here.
            if (davisCupFinal8Event != null) {
                val baseline = _data.value?.dataset?.speedBaseline
                    ?.takeIf { it.season == year }
                if (baseline == null) {
                    liveProgressByEvent = liveProgressByEvent +
                        ("daviscupfinal8" to "TML · $year player baseline unavailable")
                } else {
                    liveProgressByEvent = liveProgressByEvent +
                        ("daviscupfinal8" to "TML · reading Final 8 singles statistics")
                    runCatching {
                        val batch = tmlDavisCupClient.fetch(year)
                        val phase = batch.final8
                        val rating = ink.courtpace.index.core.live.AceRating.rate(
                            matches = phase.matches,
                            baseline = baseline,
                            discoveredMatches = phase.discoveredMatches,
                        )
                        if (rating == null) {
                            liveProgressByEvent = liveProgressByEvent + (
                                "daviscupfinal8" to buildString {
                                    append("TML · ${phase.matchesWithServeStats}/${phase.discoveredMatches} Final 8 singles have valid serve stats · ${phase.identifiedSingles} identified")
                                    if (phase.scorelessRows > 0) append(" · ${phase.scorelessRows} scoreless/pending")
                                    if (phase.noPlayRows > 0) append(" · ${phase.noPlayRows} walkover/no-play")
                                    if (phase.missingStatsMatches > 0) append(" · ${phase.missingStatsMatches} missing serve stats")
                                    if (phase.malformedStatsMatches > 0) append(" · ${phase.malformedStatsMatches} invalid-stat matches")
                                    append(" · awaiting rateable sides")
                                }
                            )
                        } else {
                            liveAceByEvent = liveAceByEvent + ("daviscupfinal8" to rating)
                            applyLiveRating(
                                "daviscupfinal8",
                                year,
                                rating.rating,
                                rating.matches,
                                rating.aceRate,
                            )
                            ratingStore.write(
                                ink.courtpace.index.core.live.CachedLiveRating(
                                    tournamentId = "daviscupfinal8",
                                    season = year,
                                    speed = rating.rating,
                                    matches = rating.matches,
                                    aceRate = rating.aceRate,
                                    asOf = java.time.Instant.now().toString(),
                                    baselineGenerated = baseline.generated,
                                    discoveredMatches = phase.discoveredMatches,
                                    adjustedServerSides = rating.adjustedServerSides,
                                    totalServerSides = rating.totalServerSides,
                                    aces = rating.aces,
                                    adjustmentIssues = rating.adjustmentIssues,
                                    source = ink.courtpace.index.core.live.CachedLiveRating.TML_DAVIS_CUP_SOURCE,
                                ),
                            )
                            liveProgressByEvent = liveProgressByEvent + (
                                "daviscupfinal8" to
                                    "TML · ${rating.matches} Final 8 matches rated · " +
                                        "${rating.adjustedServerSides}/${rating.totalServerSides} sides adjusted"
                            )
                        }
                        status(buildString {
                            append("Davis Cup Final 8 · TML · ${phase.matchesWithServeStats}/${phase.discoveredMatches} played singles with valid serve statistics · ${phase.identifiedSingles} unique singles identified")
                            if (phase.scorelessRows > 0) append(" · ${phase.scorelessRows} scoreless/pending result")
                            if (phase.noPlayRows > 0) append(" · ${phase.noPlayRows} walkover/no-play")
                            if (phase.missingStatsMatches > 0) append(" · ${phase.missingStatsMatches} completed singles missing statistics")
                            if (phase.malformedStatsMatches > 0) append(" · ${phase.malformedStatsMatches} completed singles with invalid statistics")
                        })
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        val detail = error.message ?: "TML match statistics unavailable"
                        liveProgressByEvent = liveProgressByEvent +
                            ("daviscupfinal8" to "TML · $detail")
                        status("Davis Cup Final 8 · TML · $detail")
                    }
                }
            }

            // Completed events are intentionally not recomputed with the shared live baseline.
            // Once an event leaves the active slate, its matches re-enter the season archive and
            // the Python season_ratings() pipeline publishes the final per-event LOEO CALC.
            // Until that snapshot arrives, retain the last archived value rather than replacing
            // it with a post-event estimate whose own matches now train its shared baseline.
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

        /** Pick up newly published CPI/CALC/TA snapshots promptly while the app is open. */
        const val DATASET_REFRESH_INTERVAL_MS = 5 * 60 * 1_000L

        /** Keep the event slate and ace-based reading current while the app is open. */
        const val LIVE_REFRESH_INTERVAL_MS = 5 * 60 * 1_000L

        const val MAX_COMPARE = 3
    }
}
