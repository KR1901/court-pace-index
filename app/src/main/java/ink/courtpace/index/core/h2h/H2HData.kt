package ink.courtpace.index.core.h2h

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

data class H2HPlayer(
    val id: String,
    val name: String,
    val country: String? = null,
)

data class H2HMatch(
    val id: String,
    val eventId: String,
    val eventName: String,
    val date: LocalDate?,
    val year: Int?,
    val surface: String?,
    val teamEvent: Boolean?,
    val round: String?,
    val level: String?,
    val winnerId: String,
    val winnerName: String,
    val loserId: String,
    val loserName: String,
    val score: String?,
)

data class H2HPlayerProfile(
    val player: H2HPlayer,
    val birthDate: LocalDate?,
    val heightCm: Int?,
    val currentRank: Int?,
    val rankingDate: LocalDate?,
    val bestRank: Int?,
    val rankingAvailable: Boolean = true,
)

data class H2HComparison(
    val player1: H2HPlayerProfile,
    val player2: H2HPlayerProfile,
    val meetings: List<H2HMatch>,
    val fetchedAt: LocalDate,
)

/**
 * Read-only client for TennisMyLife's published H2H and ATP player endpoints.
 *
 * Match-level records are fetched for the selected pair/players at runtime. No match database
 * or provider-derived match list is bundled in the application or committed to this repository.
 */
object H2HRepository {
    private const val BASE_URL = "https://stats.tennismylife.org"
    private const val USER_AGENT = "CourtPaceIndex-Android/4.0 (H2H player comparison)"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun searchPlayers(query: String): List<H2HPlayer> {
        val cleaned = query.trim()
        if (cleaned.length < 2) return emptyList()
        val encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8.name())
        return withContext(Dispatchers.IO) {
            val array = getJson("/api/h2h/search?q=$encoded") as? JsonArray
                ?: error("Player search returned an unexpected response")
            array.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val id = row.string("id") ?: return@mapNotNull null
                val name = row.string("atpname") ?: row.string("player") ?: return@mapNotNull null
                H2HPlayer(id = id, name = name, country = row.string("ioc"))
            }
        }
    }

    suspend fun loadComparison(first: H2HPlayer, second: H2HPlayer): H2HComparison {
        require(first.id != second.id) { "Choose two different players." }
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val encodedFirst = encode(first.id)
                val encodedSecond = encode(second.id)
                val meetingsRequest = async {
                    getMatches("/api/h2h?player1=$encodedFirst&player2=$encodedSecond")
                }
                val firstRequest = async { loadPlayer(first) }
                val secondRequest = async { loadPlayer(second) }

                H2HComparison(
                    player1 = firstRequest.await(),
                    player2 = secondRequest.await(),
                    meetings = meetingsRequest.await().sortedWith(
                        compareByDescending<H2HMatch> { it.date }
                            .thenByDescending { it.id },
                    ),
                    fetchedAt = LocalDate.now(),
                )
            }
        }
    }

    private suspend fun loadPlayer(selected: H2HPlayer): H2HPlayerProfile = coroutineScope {
        val id = encode(selected.id)
        // Optional biography/ranking endpoints must not suppress pair-meeting history.
        // Missing profile facts remain null and are rendered as Unavailable/Unranked.
        val profileRequest = async {
            try {
                getJson("/api/players?id=$id").asObject()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val rankRequest = async {
            try {
                getJson("/api/players/current-ranking?id=$id").asObject()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

        val profile = profileRequest.await()
        val rankingRoot = rankRequest.await()
        val ranking = rankingRoot?.get("ranking") as? JsonObject

        val canonical = H2HPlayer(
            id = profile?.string("id") ?: selected.id,
            name = profile?.string("atpname") ?: profile?.string("player") ?: selected.name,
            country = profile?.string("ioc") ?: selected.country,
        )
        H2HPlayerProfile(
            player = canonical,
            birthDate = parseDate(profile?.string("birthdate")),
            heightCm = parseHeight(profile?.get("height")),
            currentRank = ranking?.int("rank"),
            rankingDate = parseDate(ranking?.string("date")),
            bestRank = ranking?.int("bestRank"),
            rankingAvailable = ranking != null,
        )
    }

    private fun getMatches(path: String): List<H2HMatch> {
        val array = getJson(path) as? JsonArray
            ?: error("Match-history endpoint returned an unexpected response")
        return array.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            parseMatch(row)
        }
    }

    private fun parseMatch(row: JsonObject): H2HMatch? {
        val winnerId = row.string("winner_id") ?: return null
        val loserId = row.string("loser_id") ?: return null
        if (winnerId == loserId) return null

        val score = row.string("score")
        val status = row.bool("status")
        if (status == false || isNoPlay(score)) return null

        val tournamentId = row.string("tourney_id").orEmpty()
        val date = parseDate(row.string("tourney_date"))
        val year = date?.year ?: tournamentId.take(4).toIntOrNull() ?: row.int("year")
        val matchNumber = row.string("match_num").orEmpty()
        val eventName = row.string("tourney_name") ?: "Tournament"
        val identity = listOf(
            year?.toString().orEmpty(),
            tournamentId,
            matchNumber,
            minOf(winnerId, loserId),
            maxOf(winnerId, loserId),
        ).joinToString("|")

        return H2HMatch(
            id = identity,
            eventId = tournamentId,
            eventName = eventName,
            date = date,
            year = year,
            surface = normalizeSurface(row.string("surface")),
            teamEvent = row.bool("team_event"),
            round = row.string("round"),
            level = row.string("tourney_level"),
            winnerId = winnerId,
            winnerName = row.string("winner_name") ?: winnerId,
            loserId = loserId,
            loserName = row.string("loser_name") ?: loserId,
            score = score,
        )
    }

    private fun isNoPlay(score: String?): Boolean {
        val text = score.orEmpty().uppercase(Locale.ROOT)
        return listOf("W/O", "W.O.", "WALKOVER", "DEF", "ABD", "ABN", "ABANDONED", "WEA")
            .any { marker -> text.contains(marker) }
    }

    private fun normalizeSurface(value: String?): String? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "hard", "hard court", "hardcourt" -> "hard"
        "clay", "clay court" -> "clay"
        "grass", "grass court" -> "grass"
        "carpet" -> "carpet"
        else -> null
    }

    private fun parseHeight(value: JsonElement?): Int? {
        val raw = value.asText()?.trim()?.replace(Regex("[^0-9.]"), "") ?: return null
        val number = raw.toDoubleOrNull() ?: return null
        val centimetres = if (number in 1.40..2.20) (number * 100.0).toInt() else number.toInt()
        return centimetres.takeIf { it in 140..220 }
    }

    private fun parseDate(value: String?): LocalDate? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val candidate = raw.take(10)
        return try {
            if (candidate.length == 8 && candidate.all(Char::isDigit)) {
                LocalDate.parse(candidate, DateTimeFormatter.BASIC_ISO_DATE)
            } else {
                LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE)
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun getJson(path: String): JsonElement {
        val request = Request.Builder()
            .url(BASE_URL + path)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("TennisMyLife returned HTTP ${response.code} for H2H data.")
            }
            val body = response.body?.string()
                ?: error("TennisMyLife returned an empty H2H response.")
            return json.parseToJsonElement(body)
        }
    }

    private fun JsonElement.asObject(): JsonObject =
        this as? JsonObject ?: error("Expected a JSON object from TennisMyLife.")

    private fun JsonObject.string(key: String): String? = this[key].asText()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
            ?: this[key].asText()?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
            ?: this[key].asText()?.let { it.equals("true", true) || it == "1" }

    private fun JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
