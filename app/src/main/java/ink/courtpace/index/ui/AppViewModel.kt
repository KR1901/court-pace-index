package ink.courtpace.index.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ink.courtpace.index.CourtPaceApplication
import ink.courtpace.index.audio.BounceRecorder
import ink.courtpace.index.core.model.BallType
import ink.courtpace.index.core.model.CourtRecord
import ink.courtpace.index.core.model.SurfaceType
import ink.courtpace.index.core.physics.BounceResult
import ink.courtpace.index.core.physics.ImpactDetector
import ink.courtpace.index.core.physics.PaceEngine
import ink.courtpace.index.core.physics.PaceResult
import ink.courtpace.index.core.physics.SlideAnalyzer
import ink.courtpace.index.core.physics.SlideResult
import ink.courtpace.index.data.AppSettings
import ink.courtpace.index.sensors.SlideMeter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class TestDraft(
    val name: String = "",
    val surface: SurfaceType = SurfaceType.ACRYLIC_HARD,
    val indoor: Boolean = false,
    val location: String = "",
    val ballType: BallType = BallType.TYPE_2,
    val temperatureC: Double = 23.0,
    val dropHeightCm: Double = 254.0,
    val notes: String = "",
)

sealed interface MeasurePhase {
    data object Idle : MeasurePhase
    data class Running(val progress: Float, val hint: String) : MeasurePhase
    data class Failed(val message: String) : MeasurePhase
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CourtPaceApplication
    private val courtRepo = app.courts
    private val settingsRepo = app.settings
    private val recorder = BounceRecorder()
    private val slideMeter = SlideMeter(application)
    private val detector = ImpactDetector(BounceRecorder.SAMPLE_RATE)

    val courts: StateFlow<List<CourtRecord>> = courtRepo.courts
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    var draft by mutableStateOf(TestDraft())
        private set
    var bounce by mutableStateOf<BounceResult?>(null)
        private set
    var slide by mutableStateOf<SlideResult?>(null)
        private set
    var phase by mutableStateOf<MeasurePhase>(MeasurePhase.Idle)
        private set
    var amplitude by mutableFloatStateOf(0f)
        private set
    var liveDeceleration by mutableDoubleStateOf(0.0)
        private set
    var impactMarkers by mutableStateOf<List<Float>>(emptyList())
        private set
    var toast by mutableStateOf<String?>(null)
        private set

    private var bounceJob: Job? = null
    private var slideJob: Job? = null

    init {
        viewModelScope.launch { courtRepo.load() }
        viewModelScope.launch {
            settingsRepo.load()
            val loaded = settingsRepo.settings.value
            draft = draft.copy(
                surface = loaded.defaultSurface,
                ballType = loaded.defaultBallType,
                temperatureC = loaded.defaultTemperatureC,
                dropHeightCm = loaded.defaultDropHeightCm,
            )
        }
    }

    val slideSensorAvailable: Boolean get() = slideMeter.isAvailable

    fun updateDraft(transform: (TestDraft) -> TestDraft) {
        draft = transform(draft)
    }

    fun startNewTest() {
        val current = settingsRepo.settings.value
        draft = TestDraft(
            surface = current.defaultSurface,
            ballType = current.defaultBallType,
            temperatureC = current.defaultTemperatureC,
            dropHeightCm = current.defaultDropHeightCm,
        )
        bounce = null
        slide = null
        impactMarkers = emptyList()
        phase = MeasurePhase.Idle
    }

    fun clearToast() {
        toast = null
    }

    fun result(): PaceResult? {
        val b = bounce ?: return null
        if (!b.isUsable) return null
        val measuredCof = slide?.takeIf { it.isUsable }?.cof
        return PaceEngine.result(
            cor = b.cor,
            cof = measuredCof ?: draft.surface.fallbackCof,
            temperatureC = draft.temperatureC,
            impacts = b.impacts,
            spread = b.spread,
            cofMeasured = measuredCof != null,
            reboundHeightCm = b.reboundHeightCm,
        )
    }

    /** Records the bounce test. Call only once RECORD_AUDIO is granted. */
    fun runBounce(durationMs: Long = BOUNCE_WINDOW_MS) {
        bounceJob?.cancel()
        bounce = null
        impactMarkers = emptyList()
        phase = MeasurePhase.Running(0f, "Listening for the first impact")
        bounceJob = viewModelScope.launch {
            try {
                val samples = recorder.record(durationMs) { level ->
                    amplitude = level
                    val p = phase
                    if (p is MeasurePhase.Running && level > 0.25f) {
                        phase = p.copy(hint = "Impact detected - keep still")
                    }
                }
                if (samples.isEmpty()) {
                    phase = MeasurePhase.Failed("The microphone returned no audio. Check that no other app is using it.")
                    return@launch
                }
                phase = MeasurePhase.Running(1f, "Analysing the bounce")
                val times = detector.detect(samples)
                val analysed = PaceEngine.bounceResult(times, draft.dropHeightCm)
                impactMarkers = times.map { (it / (durationMs / 1000.0)).toFloat() }
                if (!analysed.isUsable) {
                    phase = MeasurePhase.Failed(
                        "Only ${analysed.impacts} impact(s) heard. Drop from ${draft.dropHeightCm.toInt()} cm " +
                            "with the phone on the ground, a hand's width from the bounce spot.",
                    )
                } else {
                    bounce = analysed
                    phase = MeasurePhase.Idle
                }
            } catch (t: Throwable) {
                phase = MeasurePhase.Failed(t.message ?: "The bounce test stopped unexpectedly.")
            } finally {
                amplitude = 0f
            }
        }
    }

    fun stopBounce() {
        bounceJob?.cancel()
        bounceJob = null
        amplitude = 0f
        if (phase is MeasurePhase.Running) phase = MeasurePhase.Idle
    }

    /** Records the slide test. */
    fun runSlide(durationMs: Long = SLIDE_WINDOW_MS) {
        slideJob?.cancel()
        slide = null
        phase = MeasurePhase.Running(0f, "Waiting for the slide")
        slideJob = viewModelScope.launch {
            try {
                if (!slideMeter.isAvailable) {
                    phase = MeasurePhase.Failed("This device has no accelerometer, so the friction test cannot run.")
                    return@launch
                }
                val samples = slideMeter.capture(durationMs) { decel ->
                    liveDeceleration = decel
                    val p = phase
                    if (p is MeasurePhase.Running && decel > 1.0) {
                        phase = p.copy(hint = "Sliding - reading friction")
                    }
                }
                val analysed = SlideAnalyzer.analyze(samples)
                if (analysed == null || !analysed.isUsable) {
                    phase = MeasurePhase.Failed(
                        "No steady slide found. Lay the phone flat and push it firmly along the court, " +
                            "without lifting it.",
                    )
                } else {
                    slide = analysed
                    phase = MeasurePhase.Idle
                }
            } catch (t: Throwable) {
                phase = MeasurePhase.Failed(t.message ?: "The slide test stopped unexpectedly.")
            } finally {
                liveDeceleration = 0.0
            }
        }
    }

    fun stopSlide() {
        slideJob?.cancel()
        slideJob = null
        liveDeceleration = 0.0
        if (phase is MeasurePhase.Running) phase = MeasurePhase.Idle
    }

    fun useEstimatedFriction() {
        slide = null
        phase = MeasurePhase.Idle
    }

    fun saveCourt(onSaved: (CourtRecord) -> Unit) {
        val pace = result() ?: return
        val record = CourtRecord(
            id = UUID.randomUUID().toString(),
            name = draft.name.ifBlank { "Untitled court" },
            surface = draft.surface,
            indoor = draft.indoor,
            locationLabel = draft.location,
            ballType = draft.ballType,
            ballTemperatureC = draft.temperatureC,
            dropHeightCm = draft.dropHeightCm,
            cor = pace.cor,
            corAdjusted = pace.corAdjusted,
            cof = pace.cof,
            cofMeasured = pace.cofMeasured,
            cpr = pace.cpr,
            cpi = pace.cpi,
            reboundHeightCm = pace.reboundHeightCm,
            impacts = bounce?.impacts ?: 0,
            corSpread = bounce?.spread ?: 0.0,
            confidence = pace.confidence,
            notes = draft.notes,
            createdAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            courtRepo.upsert(record)
            onSaved(record)
        }
    }

    fun deleteCourt(id: String) {
        viewModelScope.launch {
            courtRepo.delete(id)
            toast = "Court removed"
        }
    }

    fun exportPayload(): String {
        return runCatching { kotlinx.serialization.json.Json.encodeToString(courts.value) }
            .getOrDefault("[]")
    }

    fun importPayload(payload: String) {
        viewModelScope.launch {
            runCatching { courtRepo.importJson(payload) }
                .onSuccess { toast = "Imported $it court(s)" }
                .onFailure { toast = "That file is not a Court Pace export" }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    override fun onCleared() {
        super.onCleared()
        bounceJob?.cancel()
        slideJob?.cancel()
    }

    companion object {
        const val BOUNCE_WINDOW_MS = 6_000L
        const val SLIDE_WINDOW_MS = 3_200L
    }
}
