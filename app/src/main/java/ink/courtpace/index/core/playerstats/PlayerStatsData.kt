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
data class PlayerStatsMeta(
    val generated: String = "",
    val firstSeason: Int = 1967,
    val lastSeason: Int = 0,
    val players: Int = 0,
    val playersWithHeight: Int = 0,
    val playersInCurrentTop100: Int = 0,
    val currentTop100AsOf: String = "",
    val currentTop100Source: String = "",
    val source: String = "",
    val sourceUrl: String = "",
    val seasonDefinition: String = "",
    val statistics: String = "",
    val sampleNote: String = "",
    val statsAvailableSeasons: List<Int> = emptyList(),
    val tourLevels: List<String> = emptyList(),
    val usage: String = "",
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
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): PlayerStatsData =
        context.assets.open("player_stats.json").bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString<PlayerStatsData>(reader.readText())
        }
}
