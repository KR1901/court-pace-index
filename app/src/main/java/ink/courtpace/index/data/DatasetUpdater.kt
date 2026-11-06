package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.BuildConfig
import ink.courtpace.index.core.data.DatasetPolicy
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.PaceDataset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class CachedDataset(
    val fetchedAt: Long,
    val dataset: PaceDataset,
)

sealed interface RefreshResult {
    data class Success(val dataset: PaceDataset, val fetchedAt: Long) : RefreshResult
    data object Unchanged : RefreshResult
    data class Failure(val message: String) : RefreshResult
}

/**
 * Pulls a newer dataset from the project's published copy and keeps it in app storage.
 *
 * The URL points at a JSON file in the repository that a scheduled job regenerates, rather
 * than at a third-party page: the schema is ours, so a redesign upstream cannot break the
 * app, and the app never scrapes anyone's site.
 */
class DatasetUpdater(private val context: Context) {

    private val cacheFile = File(context.filesDir, CACHE_NAME)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A previously downloaded dataset, if one is stored. */
    suspend fun cached(): CachedDataset? = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext null
        runCatching { json.decodeFromString<CachedDataset>(cacheFile.readText()) }.getOrNull()
    }

    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        val body = runCatching { download() }
            .getOrElse { return@withContext RefreshResult.Failure(it.friendly()) }

        val dataset = runCatching { json.decodeFromString<PaceDataset>(body) }
            .getOrElse { return@withContext RefreshResult.Failure("The published file was not readable.") }

        if (!DatasetPolicy.isAcceptable(dataset)) {
            return@withContext RefreshResult.Failure("The published file did not pass its own checks.")
        }

        val previous = cached()
        if (previous != null && previous.dataset.readings.size == dataset.readings.size &&
            previous.dataset.meta.generated == dataset.meta.generated
        ) {
            return@withContext RefreshResult.Unchanged
        }

        val now = System.currentTimeMillis()
        val stored = runCatching {
            cacheFile.writeText(json.encodeToString(CachedDataset(fetchedAt = now, dataset = dataset)))
        }
        if (stored.isFailure) {
            return@withContext RefreshResult.Failure("Could not store the update on this device.")
        }
        RefreshResult.Success(dataset, now)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        cacheFile.delete()
        Unit
    }

    private fun download(): String {
        val connection = (URL(SOURCE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CourtPace/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("Server returned HTTP $code.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun Throwable.friendly(): String = when (this) {
        is java.net.UnknownHostException -> "No connection."
        is java.net.SocketTimeoutException -> "The server took too long to answer."
        else -> message ?: "The update could not be downloaded."
    }

    companion object {
        private const val CACHE_NAME = "dataset_cache.json"
        const val SOURCE_URL =
            "https://raw.githubusercontent.com/KR1901/court-pace-index/main/data/latest.json"
    }
}

/** Bundled dataset reader. */
object BundledDataset {
    const val ASSET = "pace_dataset.json"

    fun load(context: Context): PaceData {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val parsed = PaceData.parse(text)
        // Historical builds bundled the former pooled-rate live estimate. Live ratings are
        // now calculated from ATP match stats plus the opponent-adjustment baseline at runtime.
        val withoutStaticLiveRatings = parsed.dataset.copy(
            readings = parsed.dataset.readings.filterNot { it.src == "live" && it.speed != null },
        )
        return PaceData(withoutStaticLiveRatings)
    }
}
