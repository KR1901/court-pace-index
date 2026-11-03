package ink.courtpace.index.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ink.courtpace.index.core.analytics.PaceAnalytics
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {

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

    init {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    application.assets.open(ASSET).bufferedReader().use { it.readText() }
                }
            }.onSuccess { text ->
                runCatching { PaceData.parse(text) }
                    .onSuccess { parsed ->
                        _data.value = parsed
                        season = parsed.latestSeason
                        if (parsed.seasons.isNotEmpty()) {
                            // Start on the newest season that actually holds CPI readings.
                            season = parsed.seasons.lastOrNull { s ->
                                parsed.entries(s, Metric.CPI).isNotEmpty()
                            } ?: parsed.latestSeason
                        }
                    }
                    .onFailure { _error.value = "The bundled dataset could not be read: ${it.message}" }
            }.onFailure {
                _error.value = "The bundled dataset is missing from the APK."
            }
        }
    }

    fun selectSeason(value: Int) {
        season = value
    }

    fun selectMetric(value: Metric) {
        metric = value
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

    fun ranking(data: PaceData) = PaceAnalytics.ranking(data, season, metric, surfaces)

    companion object {
        private const val ASSET = "pace_dataset.json"
        const val MAX_COMPARE = 3
    }
}
