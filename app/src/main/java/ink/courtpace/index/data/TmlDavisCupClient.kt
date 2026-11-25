package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.live.AceRating
import ink.courtpace.index.core.live.TmlDavisCupStatsParser
import ink.courtpace.index.core.model.LiveSpeedBaseline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Retrieves the current-season TML CSV snapshots and calculates the Davis Cup season-to-date
 * target using the same opponent-adjusted method and bundled ATP baseline as the other live rows.
 *
 * Raw CSV files live only in the app's private cache directory. They are not copied into the
 * dataset, exported, or uploaded. The annual file is cached for a day; the small ongoing feed is
 * refreshed on the app's existing five-minute live polling cycle.
 */
class TmlDavisCupClient(context: Context) {

    data class Snapshot(
        val rating: AceRating.Result,
        val asOf: String,
        val source: String = "TennisMyLife ATP Match Database",
    )

    private data class CachedText(
        val text: String,
        val fetchedAtMillis: Long,
    )

    private val directory = File(context.applicationContext.filesDir, "tml/davis-cup")
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAceRating(
        season: Int,
        baseline: LiveSpeedBaseline,
    ): Result<Snapshot> = withContext(Dispatchers.IO) {
        try {
            require(baseline.season == season) {
                "TML Davis Cup season $season does not match baseline season ${baseline.season}"
            }
            val annual = readSnapshot(
                fileName = "$season.csv",
                url = "${BASE_URL}/$season.csv",
                refreshAfterMillis = ANNUAL_REFRESH_MS,
                staleLimitMillis = ANNUAL_STALE_LIMIT_MS,
            )
            val ongoing = readSnapshot(
                fileName = "ongoing_tourneys.csv",
                url = ONGOING_URL,
                refreshAfterMillis = ONGOING_REFRESH_MS,
                staleLimitMillis = ONGOING_STALE_LIMIT_MS,
            )
            val matches = TmlDavisCupStatsParser.parseSeason(
                snapshots = listOf(annual.text, ongoing.text),
                season = season,
            )
            if (matches.isEmpty()) {
                throw IOException("TML has not published usable completed Davis Cup singles statistics for $season")
            }
            val rating = AceRating.rate(matches, baseline)
                ?: throw IOException("TML Davis Cup rows contain no opponent-adjustable server sides")
            Result.success(
                Snapshot(
                    rating = rating.copy(
                        discoveredMatches = matches.size,
                        totalServerSides = matches.size * 2,
                    ),
                    asOf = Instant.ofEpochMilli(ongoing.fetchedAtMillis).toString(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun readSnapshot(
        fileName: String,
        url: String,
        refreshAfterMillis: Long,
        staleLimitMillis: Long,
    ): CachedText {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create private TML cache directory")
        }
        val file = File(directory, fileName)
        val now = System.currentTimeMillis()
        val cachedAge = if (file.isFile) (now - file.lastModified()).coerceAtLeast(0L) else Long.MAX_VALUE
        if (file.isFile && cachedAge <= refreshAfterMillis) {
            return CachedText(file.readText(Charsets.UTF_8), file.lastModified())
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/csv")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("TML returned HTTP ${response.code} for $fileName")
                }
                val body = response.body?.string()
                    ?: throw IOException("TML returned an empty response for $fileName")
                if (!body.lineSequence().firstOrNull().orEmpty().removePrefix("\uFEFF").startsWith("tourney_id,")) {
                    throw IOException("TML returned an unexpected CSV header for $fileName")
                }
                val temporary = File(directory, "$fileName.tmp")
                temporary.writeText(body, Charsets.UTF_8)
                if (!temporary.renameTo(file)) {
                    temporary.copyTo(file, overwrite = true)
                    temporary.delete()
                }
                CachedText(body, file.lastModified().takeIf { it > 0 } ?: now)
            }
        } catch (error: Exception) {
            if (file.isFile && cachedAge <= staleLimitMillis) {
                return CachedText(file.readText(Charsets.UTF_8), file.lastModified())
            }
            throw error
        }
    }

    companion object {
        const val BASE_URL = "https://stats.tennismylife.org/data"
        const val ONGOING_URL = "$BASE_URL/ongoing_tourneys.csv"
        const val USER_AGENT = "CourtPaceIndex/1.0 (non-commercial research)"
        const val ANNUAL_REFRESH_MS = 24 * 60 * 60 * 1_000L
        const val ANNUAL_STALE_LIMIT_MS = 7 * 24 * 60 * 60 * 1_000L
        const val ONGOING_REFRESH_MS = 4 * 60 * 1_000L
        const val ONGOING_STALE_LIMIT_MS = 2 * 60 * 60 * 1_000L
    }
}
