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
 * Fetches TML's source manifest and only downloads changed ATP Tour, Challenger and qualifying CSVs.
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
            }

            // The manifest may expose qualifying files with an atp_quali/ path prefix.
            // Resolve by exact basename while retaining the trusted manifest URL.
            fun findRemote(filename: String): RemoteFile? = remote.firstOrNull { (name, file) ->
                name.replace("\\", "/").substringAfterLast("/") == filename ||
                    file.url.substringBefore("?").substringAfterLast("/") == filename
            }?.second

            val year = LocalDate.now().year
            var addedMatches = 0
            var enrichedMatches = 0
            var changedSources = 0

            fun processSource(
                filename: String,
                sourceKind: String,
                previousMtime: String,
                updateMtime: (String) -> Unit,
            ) {
                val source = findRemote(filename) ?: return
                if (source.modified == previousMtime) return
                val rows = parse(getText(source.url))
                val update = PlayerStatsLiveAggregator.apply(
                    baseline = data,
                    rows = rows,
                    season = year,
                    updatedAt = Instant.now().toString(),
                    sourceKind = sourceKind,
                )
                data = update.data
                updateMtime(source.modified)
                addedMatches += update.addedMatches
                enrichedMatches += update.enrichedMatches
                changedSources++
            }

            // Tour matches update the original Rankings ledger and the expanded
            // Height Groups ledger. Their independent keys prevent double counting.
            processSource(
                filename = year.toString() + ".csv",
                sourceKind = "tour",
                previousMtime = data.meta.currentSeasonSourceMtime,
                updateMtime = { mtime ->
                    data = data.copy(meta = data.meta.copy(currentSeasonSourceMtime = mtime))
                },
            )
            processSource(
                filename = "ongoing_tourneys.csv",
                sourceKind = "tour",
                previousMtime = data.meta.ongoingSourceMtime,
                updateMtime = { mtime ->
                    data = data.copy(meta = data.meta.copy(ongoingSourceMtime = mtime))
                },
            )

            // Challenger and qualifying matches feed Height Groups only.
            processSource(
                filename = year.toString() + "_challenger.csv",
                sourceKind = "challenger",
                previousMtime = data.meta.currentSeasonChallengerSourceMtime,
                updateMtime = { mtime ->
                    data = data.copy(meta = data.meta.copy(currentSeasonChallengerSourceMtime = mtime))
                },
            )
            processSource(
                filename = year.toString() + "_atp_quali.csv",
                sourceKind = "qualifying",
                previousMtime = data.meta.currentSeasonQualifyingSourceMtime,
                updateMtime = { mtime ->
                    data = data.copy(meta = data.meta.copy(currentSeasonQualifyingSourceMtime = mtime))
                },
            )
            processSource(
                filename = "ch_ongoing_tourney.csv",
                sourceKind = "challenger",
                previousMtime = data.meta.ongoingChallengerSourceMtime,
                updateMtime = { mtime ->
                    data = data.copy(meta = data.meta.copy(ongoingChallengerSourceMtime = mtime))
                },
            )

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
