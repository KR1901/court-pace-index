package ink.courtpace.index.core.playerstats

import android.content.Context
import ink.courtpace.index.core.playerstats.PlayerStatsLiveCsvParser.parse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class PlayerStatsLiveRefreshResult(
    val data: PlayerStatsData,
    val checkedAt: String,
    val addedMatches: Int,
    val enrichedMatches: Int,
    val changedSources: Int,
)

data class PlayerStatsLiveState(
    val data: PlayerStatsData,
    val checkedAt: String? = null,
    val addedMatches: Int = 0,
    val enrichedMatches: Int = 0,
    val error: String? = null,
)

/**
 * Fetches TML's source manifest and only downloads ATP CSVs whose published mtime changed.
 * This module is independent from AppViewModel, CPI, Surface Speed and their refresh loops.
 */
object PlayerStatsLiveUpdater {
    private const val MANIFEST_URL = "https://stats.tennismylife.org/api/data-files"
    private const val SOURCE_PREFIX = "https://stats.tennismylife.org/data/"
    private const val USER_AGENT = "CourtPaceIndex-PrivatePlayerStats/1.0 (personal research)"

    private val mutex = Mutex()
    @Volatile private var memoryData: PlayerStatsData? = null
    @Volatile private var lastFailure: String? = null
    private val mutableState = MutableStateFlow<PlayerStatsLiveState?>(null)
    val state: StateFlow<PlayerStatsLiveState?> = mutableState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private data class RemoteFile(
        val url: String,
        val modified: String,
    )

    /** Publish the cache/archive immediately so the Player Stats screen never waits for network. */
    fun publishInitial(data: PlayerStatsData) {
        if (mutableState.value == null) {
            memoryData = memoryData ?: data
            mutableState.value = PlayerStatsLiveState(
                data = memoryData ?: data,
                error = lastFailure,
            )
        }
    }

    fun reportFailure(message: String) {
        lastFailure = message
        mutableState.value = mutableState.value?.copy(error = message)
    }

    suspend fun refresh(context: Context): PlayerStatsLiveRefreshResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            var data = memoryData ?: PlayerStatsRepository.load(context)
            memoryData = data
            val manifest = json.parseToJsonElement(getText(MANIFEST_URL)).jsonObject
            val files = manifest["files"]?.jsonArray
                ?: error("TennisMyLife file manifest has no files array")
            val remote = files.mapNotNull { element ->
                val item = element.jsonObject
                val name = item["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = item["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val modified = item["mtime"]?.jsonPrimitive?.content.orEmpty()
                if (modified.isBlank()) return@mapNotNull null
                require(url.startsWith(SOURCE_PREFIX)) {
                    "Unexpected ATP data URL in source manifest"
                }
                name to RemoteFile(url, modified)
            }.toMap()

            val year = LocalDate.now().year
            var addedMatches = 0
            var enrichedMatches = 0
            var changedSources = 0

            // First reconcile the annual file. The app's baseline carries all match keys already
            // present when that asset was built; only genuinely new results are appended.
            val annualName = year.toString() + ".csv"
            val annual = remote[annualName]
            if (annual != null && annual.modified != data.meta.currentSeasonSourceMtime) {
                val rows = parse(getText(annual.url))
                val update = PlayerStatsLiveAggregator.apply(
                    baseline = data,
                    rows = rows,
                    season = year,
                    updatedAt = Instant.now().toString(),
                )
                data = update.data.copy(
                    meta = update.data.meta.copy(currentSeasonSourceMtime = annual.modified),
                )
                addedMatches += update.addedMatches
                enrichedMatches += update.enrichedMatches
                changedSources++
            }

            // The live CSV may overlap the annual archive. Both use the same stable ID key set.
            val ongoing = remote["ongoing_tourneys.csv"]
            if (ongoing != null && ongoing.modified != data.meta.ongoingSourceMtime) {
                val rows = parse(getText(ongoing.url))
                val update = PlayerStatsLiveAggregator.apply(
                    baseline = data,
                    rows = rows,
                    season = year,
                    updatedAt = Instant.now().toString(),
                )
                data = update.data.copy(
                    meta = update.data.meta.copy(ongoingSourceMtime = ongoing.modified),
                )
                addedMatches += update.addedMatches
                changedSources++
            }

            if (changedSources > 0) {
                PlayerStatsRepository.save(context, data)
            }
            memoryData = data

            val result = PlayerStatsLiveRefreshResult(
                data = data,
                checkedAt = Instant.now().toString(),
                addedMatches = addedMatches,
                enrichedMatches = enrichedMatches,
                changedSources = changedSources,
            )
            lastFailure = null
            mutableState.value = PlayerStatsLiveState(
                data = data,
                checkedAt = result.checkedAt,
                addedMatches = result.addedMatches,
                enrichedMatches = result.enrichedMatches,
                error = null,
            )
            result
        }
    }

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/csv,*/*")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("TennisMyLife returned HTTP " + response.code + " for " + url.substringAfterLast('/'))
            }
            return response.body?.string()
                ?: error("TennisMyLife returned an empty response for " + url.substringAfterLast('/'))
        }
    }
}
