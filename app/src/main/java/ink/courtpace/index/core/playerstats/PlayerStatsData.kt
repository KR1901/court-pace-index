package ink.courtpace.index.core.playerstats

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlayerStatsData(
    val meta: PlayerStatsMeta,
    val players: List<PlayerStatsPlayer>,
)

@Serializable
data class PlayerTop100SeasonRank(
    val y: Int,
    val rank: Int,
)

@Serializable
data class PlayerTop100SeasonSnapshot(
    val year: Int,
    val asOf: String,
    val source: String,
    /** Identified players whose stable TML IDs can be joined to match/history records. */
    val players: Int = 100,
    /** Ranked source rows without a stable player ID; never merged into a fake person. */
    val unresolvedPlayers: Int = 0,
    /** Total rank-100-or-better source rows, including unresolved IDs and co-ranked players. */
    val rankedEntries: Int = 100,
)

@Serializable
data class PlayerTop1000SeasonRank(
    val y: Int,
    val rank: Int,
)

@Serializable
data class PlayerTop1000SeasonSnapshot(
    val year: Int,
    val asOf: String,
    val source: String,
    /** Identified players ranked 1–1000, retaining ties at the cutoff. */
    val players: Int = 1000,
    /** Rank-1000-or-better source rows without stable IDs. */
    val unresolvedPlayers: Int = 0,
    /** Total rank-1000-or-better rows, including unresolved IDs and ties. */
    val rankedEntries: Int = 1000,
    /** Highest rank position returned by the dated source snapshot. */
    val maxRank: Int = 0,
    /** Unresolved rows ranked 1–500, used for the matching cohort note. */
    val unresolvedTop500: Int = 0,
)

@Serializable
data class PlayerStatsMeta(
    val generated: String = "",
    val firstSeason: Int = 1967,
    val lastSeason: Int = 0,
    val players: Int = 0,
    val playersWithHeight: Int = 0,
    val playersInCurrentTop100: Int = 0,
    val currentTop100AsOf: String = "",
    val currentTop100Source: String = "",
    val firstTop100Season: Int = 1973,
    val top100SeasonSnapshots: List<PlayerTop100SeasonSnapshot> = emptyList(),
    val top100SeasonPolicy: String = "",
    val playersInCurrentTop500: Int = 0,
    val playersInCurrentTop1000: Int = 0,
    val firstTop1000Season: Int = 1973,
    val top1000SeasonSnapshots: List<PlayerTop1000SeasonSnapshot> = emptyList(),
    val top1000SeasonPolicy: String = "",
    val source: String = "",
    val sourceUrl: String = "",
    val seasonDefinition: String = "",
    val statistics: String = "",
    val sampleNote: String = "",
    val statsAvailableSeasons: List<Int> = emptyList(),
    val tourLevels: List<String> = emptyList(),
    val usage: String = "",
    val playersWithEligibleArchiveResults: Int = 0,
    val currentSeasonSourceMtime: String = "",
    val currentSeasonMatchKeys: List<String> = emptyList(),
    val currentSeasonStatMatchKeys: List<String> = emptyList(),
    val ongoingSourceMtime: String = "",
    val liveUpdatedAt: String? = null,
    val liveMatchCount: Int = 0,
    val liveEnrichedMatchCount: Int = 0,
)

@Serializable
data class PlayerStatsPlayer(
    val id: String,
    val name: String,
    val country: String? = null,
    val heightCm: Int? = null,
    val heightSource: String? = null,
    val currentRank: Int? = null,
    val seasons: List<PlayerSeasonStats> = emptyList(),
    val top100RanksBySeason: List<PlayerTop100SeasonRank> = emptyList(),
    /** Year-specific rank membership through rank 1000; older assets may omit this field. */
    val top1000RanksBySeason: List<PlayerTop1000SeasonRank> = emptyList(),
)

@Serializable
data class PlayerSeasonStats(
    val y: Int,
    val b: Map<String, PlayerStatsBucket> = emptyMap(),
)

@Serializable
data class PlayerStatsBucket(
    val mp: Int = 0,
    val w: Int = 0,
    val l: Int = 0,
    val sp: Int = 0,
    val sw: Int = 0,
    val rp: Int = 0,
    val rw: Int = 0,
    val sm: Int = 0,
)

object PlayerStatsRepository {
    private const val CACHE_FILE = "player_stats_live_cache.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** Prefer the persistent live cache only when it belongs to this exact bundled baseline. */
    fun load(context: Context): PlayerStatsData {
        val bundled = loadBundled(context)
        val cache = android.util.AtomicFile(java.io.File(context.filesDir, CACHE_FILE))
        val cached = runCatching {
            cache.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                json.decodeFromString<PlayerStatsData>(reader.readText())
            }
        }.getOrNull()
        return cached?.takeIf { it.meta.generated == bundled.meta.generated } ?: bundled
    }

    fun loadBundled(context: Context): PlayerStatsData =
        context.assets.open("player_stats.json").bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString<PlayerStatsData>(reader.readText())
        }

    /** Atomic cache replacement prevents an interrupted process from corrupting the last good snapshot. */
    fun save(context: Context, data: PlayerStatsData) {
        val cache = android.util.AtomicFile(java.io.File(context.filesDir, CACHE_FILE))
        val stream = cache.startWrite()
        try {
            stream.write(json.encodeToString(data).toByteArray(Charsets.UTF_8))
            cache.finishWrite(stream)
        } catch (error: Exception) {
            cache.failWrite(stream)
            throw error
        }
    }
}
