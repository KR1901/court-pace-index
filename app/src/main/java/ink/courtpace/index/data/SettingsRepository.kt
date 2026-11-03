package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.model.BallType
import ink.courtpace.index.core.model.SurfaceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light"),
}

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val metricUnits: Boolean = true,
    val defaultSurface: SurfaceType = SurfaceType.ACRYLIC_HARD,
    val defaultBallType: BallType = BallType.TYPE_2,
    val defaultDropHeightCm: Double = 254.0,
    val defaultTemperatureC: Double = 23.0,
    val haptics: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (file.exists()) {
            _settings.value = runCatching {
                json.decodeFromString<AppSettings>(file.readText())
            }.getOrElse { AppSettings() }
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        val next = transform(_settings.value)
        file.writeText(json.encodeToString(next))
        _settings.value = next
    }

    private companion object {
        const val FILE_NAME = "settings.json"
    }
}
