package ink.courtpace.index.data

import java.util.Base64
import ink.courtpace.index.core.live.AtpMatchStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads ATP Stats Plus directly from the Infosys JSON feed.
 *
 * ATP's browser page is only a presentation layer. The Infosys response contains a setStats.set0
 * array with one row per statistic and player1/player2 values. Some responses wrap that JSON in
 * an AES-CBC envelope (lastModified + base64 response); both the API and static feed variants are
 * supported because ATP has used both.
 */
internal class AtpInfosysStatsClient {

    enum class MatchDataState {
        PLAYED,
        NO_PLAYED_DATA,
        IN_PROGRESS,
        DOUBLES,
        INCOMPLETE_STATS,
        UNAVAILABLE,
    }

    data class ReadResult(
        val stats: AtpMatchStats?,
        val payloadBytes: Int,
        val endpoint: String?,
        val diagnostic: String?,
        val state: MatchDataState = MatchDataState.UNAVAILABLE,
    )

    /** The static JSON feed is the established public ATP Stats Plus endpoint. */
    internal fun endpointsForMatch(year: Int, eventId: Int, matchId: String): List<String> {
        val id = matchId.uppercase()
        return listOf(
            "https://itp-atp-sls.infosys-platforms.com/static/prod/stats-plus/" +
                "$year/$eventId/$id/keystats.json",
            "https://itp-atp-sls.infosys-platforms.com/prod/api/stats-plus/v1/keystats/" +
                "year/$year/eventId/$eventId/matchId/$id",
        )
    }

    suspend fun read(year: Int, eventId: Int, matchId: String): ReadResult =
        withContext(Dispatchers.IO) {
            val endpoints = endpointsForMatch(year, eventId, matchId)
            val failures = mutableListOf<String>()
            var lastPayloadBytes = 0
            var lastEndpoint: String? = null
            var lastState = MatchDataState.UNAVAILABLE
            for (endpoint in endpoints) {
                val response = request(endpoint)
                if (response.first == null) {
                    failures += "${endpoint.substringAfter("/prod/")}: ${response.second}"
                    continue
                }
                val raw = response.first!!
                lastPayloadBytes = raw.toByteArray(StandardCharsets.UTF_8).size
                lastEndpoint = endpoint
                try {
                    val envelope = JSONObject(raw)
                    val payload = if (envelope.has("setStats")) {
                        envelope
                    } else {
                        decryptEnvelope(envelope)
                    }
                    val stats = parseStats(payload)
                    lastState = classifyPayload(payload, stats != null)
                    if (stats != null && lastState == MatchDataState.PLAYED) {
                        return@withContext ReadResult(
                            stats = stats,
                            payloadBytes = lastPayloadBytes,
                            endpoint = endpoint,
                            diagnostic = null,
                            state = MatchDataState.PLAYED,
                        )
                    }
                    failures += "${endpoint.substringAfter("/prod/")}: ${payloadDiagnostic(payload, lastState)}"
                } catch (error: Exception) {
                    failures += "${endpoint.substringAfter("/prod/")}: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(100)}"
                }
            }
            ReadResult(
                stats = null,
                payloadBytes = lastPayloadBytes,
                endpoint = lastEndpoint,
                diagnostic = failures.joinToString(" | ").take(280),
                state = lastState,
            )
        }

    /**
     * ATP's own Infosys scraper retries failed requests up to three times with exponential
     * backoff. A single 403 can be a transient edge refusal; do not permanently drop a match
     * after one response. Each endpoint still has its own bounded retry budget.
     */
    private fun request(url: String): Pair<String?, String> {
        var last = null as Pair<String?, String>?
        for (attempt in 1..MAX_HTTP_ATTEMPTS) {
            last = requestOnce(url)
            if (last.first != null || !shouldRetry(last.second)) {
                return last
            }
            if (attempt == MAX_HTTP_ATTEMPTS) break
            val retryAfterSeconds = Regex("""retry-after=([0-9]+)""", RegexOption.IGNORE_CASE)
                .find(last.second)?.groupValues?.get(1)?.toLongOrNull()
            val exponentialDelay = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
            val delayMs = (retryAfterSeconds?.times(1_000L) ?: exponentialDelay)
                .coerceAtMost(MAX_RETRY_DELAY_MS)
            try {
                Thread.sleep(delayMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return null to "Interrupted while retrying Infosys request"
            }
        }
        val final = last ?: (null to "No Infosys request attempt was made")
        return if (final.first == null && MAX_HTTP_ATTEMPTS > 1) {
            null to "${final.second} (after $MAX_HTTP_ATTEMPTS attempts)"
        } else {
            final
        }
    }

    private fun shouldRetry(diagnostic: String): Boolean {
        val status = Regex("""HTTP ([0-9]{3})""").find(diagnostic)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            status == null -> diagnostic.contains("Exception", ignoreCase = true) ||
                diagnostic.contains("timeout", ignoreCase = true) ||
                diagnostic.contains("reset", ignoreCase = true)
            else -> shouldRetryStatus(status)
        }
    }

    internal fun shouldRetryStatus(status: Int): Boolean =
        status == 403 || status == 408 || status == 425 || status == 429 ||
            status in 500..599

    private fun requestOnce(url: String): Pair<String?, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            // Match the minimal headers used by the established ATP Infosys scraper.
            // Synthetic browser Origin/Referer headers can trigger a different edge policy.
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status in 200..299 && body.isNotBlank()) {
                body to "HTTP $status"
            } else {
                val server = connection.getHeaderField("Server")?.take(48)
                val retryAfter = connection.getHeaderField("Retry-After")?.take(16)
                val sample = body.trim().take(MAX_ERROR_BODY_CHARS)
                val details = buildList {
                    add("HTTP $status (${body.length} chars)")
                    if (!server.isNullOrBlank()) add("server=$server")
                    if (!retryAfter.isNullOrBlank()) add("retry-after=$retryAfter")
                    if (sample.isNotBlank()) add("body=$sample")
                }.joinToString("; ")
                null to details
            }
        } catch (error: Exception) {
            null to "${error.javaClass.simpleName}: ${error.message.orEmpty().take(100)}"
        } finally {
            connection.disconnect()
        }
    }

    private fun decryptEnvelope(envelope: JSONObject): JSONObject {
        return JSONObject(
            decryptResponse(
                lastModified = envelope.getLong("lastModified"),
                encoded = envelope.getString("response"),
            ),
        )
    }

    internal fun decryptResponse(lastModified: Long, encoded: String): String {
        val keyText = cipherKey(lastModified)
        val key = keyText.toByteArray(StandardCharsets.UTF_8)
        val iv = keyText.uppercase().toByteArray(StandardCharsets.UTF_8)
        require(key.size == 16 && iv.size == 16) { "Invalid ATP cipher key length" }

        val encrypted = Base64.getMimeDecoder().decode(encoded)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        val padded = cipher.doFinal(encrypted)
        require(padded.isNotEmpty()) { "Empty decrypted ATP response" }
        val padding = padded.last().toInt() and 0xff
        require(padding in 1..16 && padding <= padded.size) { "Invalid ATP response padding" }
        for (i in padded.size - padding until padded.size) {
            require((padded[i].toInt() and 0xff) == padding) { "Invalid ATP response padding bytes" }
        }
        return String(padded, 0, padded.size - padding, StandardCharsets.UTF_8).trim()
    }

    /**
     * Port of ATP Infosys' published scraper key derivation:
     * '#' + base36(hex(timestamp)) + base24((year+reverse(year))*(day+reverse(day))) + '$'
     * The middle string is padded/truncated to fourteen characters.
     */
    internal fun cipherKey(lastModified: Long): String {
        val date = Instant.ofEpochMilli(lastModified).atZone(ZoneOffset.UTC)
        val day = date.dayOfMonth
        val reverseDay = day.toString().padStart(2, '0').reversed().toInt()
        val year = date.year
        val reverseYear = year.toString().reversed().toInt()
        val timestampAsHex = BigInteger(lastModified.toString(), 16)
        val first = timestampAsHex.toString(36)
        val second = toRadix(BigInteger.valueOf((year + reverseYear).toLong() * (day + reverseDay)), 24)
        var middle = first + second
        middle = when {
            middle.length < 14 -> middle.padEnd(14, '0')
            middle.length > 14 -> middle.substring(0, 14)
            else -> middle
        }
        return "#$middle$"
    }

    private fun toRadix(value: BigInteger, radix: Int): String {
        require(radix in 2..36)
        if (value == BigInteger.ZERO) return "0"
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var current = value
        val base = BigInteger.valueOf(radix.toLong())
        val output = StringBuilder()
        while (current > BigInteger.ZERO) {
            val division = current.divideAndRemainder(base)
            output.append(digits[division[1].toInt()])
            current = division[0]
        }
        return output.reverse().toString()
    }

    /**
     * Classify a JSON match independently of the event-specific ID. A retirement is PLAYED when
     * either set0 or any played set has the required stats. Empty completed data with zero sets
     * means no play (typically a walkover); it is not treated as a failed retirement.
     */
    internal fun classifyPayload(payload: JSONObject, statsAvailable: Boolean): MatchDataState {
        val matchCompleted = payload.takeIf { it.has("matchCompleted") }
            ?.optBoolean("matchCompleted")
        val isDoubles = payload.takeIf { it.has("isDoubles") }?.optBoolean("isDoubles")
        val setsCompleted = payload.takeIf { it.has("setsCompleted") }
            ?.optInt("setsCompleted")
        val statsRoot = payload.optJSONObject("setStats")
        val hasPlayedSetData = (1..5).any { setNumber ->
            (statsRoot?.optJSONArray("set$setNumber")?.length() ?: 0) > 0
        }

        return classifyMatchData(
            matchCompleted = matchCompleted,
            isDoubles = isDoubles,
            setsCompleted = setsCompleted,
            hasPlayedSetData = hasPlayedSetData,
            statsAvailable = statsAvailable,
        )
    }

    internal fun classifyMatchData(
        matchCompleted: Boolean?,
        isDoubles: Boolean?,
        setsCompleted: Int?,
        hasPlayedSetData: Boolean,
        statsAvailable: Boolean,
    ): MatchDataState = when {
        isDoubles == true -> MatchDataState.DOUBLES
        matchCompleted == false -> MatchDataState.IN_PROGRESS
        statsAvailable -> MatchDataState.PLAYED
        hasPlayedSetData -> MatchDataState.INCOMPLETE_STATS
        matchCompleted == true && setsCompleted == 0 ->
            MatchDataState.NO_PLAYED_DATA
        else -> MatchDataState.INCOMPLETE_STATS
    }

    private fun payloadDiagnostic(payload: JSONObject, state: MatchDataState): String {
        val completed = payload.opt("matchCompleted")
        val doubles = payload.opt("isDoubles")
        val sets = payload.opt("setsCompleted")
        return when (state) {
            MatchDataState.NO_PLAYED_DATA ->
                "completed result has zero played sets (walkover/no-play)"
            MatchDataState.IN_PROGRESS -> "match is not marked completed"
            MatchDataState.DOUBLES -> "payload is marked doubles"
            MatchDataState.INCOMPLETE_STATS ->
                "match completed=$completed, doubles=$doubles, setsCompleted=$sets; " +
                    "missing complete Aces/Service Points Won rows"
            MatchDataState.PLAYED -> "match stats available"
            MatchDataState.UNAVAILABLE -> "no Stats Plus payload"
        }
    }

    internal data class StatRow(
        val name: String,
        val player1: String,
        val player2: String,
    )

    internal fun parseStats(payload: JSONObject): AtpMatchStats? {
        val statsRoot = payload.optJSONObject("setStats") ?: return null
        val players = payload.optJSONArray("players")
        val firstName = players?.optJSONObject(0)?.optString("player1Name")
            ?.takeIf { it.isNotBlank() } ?: "Player 1"
        val secondName = players?.optJSONObject(1)?.optString("player1Name")
            ?.takeIf { it.isNotBlank() } ?: "Player 2"

        val wholeMatchRows = statsRoot.optJSONArray("set0")?.let(::rowsFromJson)
        // Inspect all possible set arrays, not just setsCompleted: a retirement can contain
        // statistics from a partially played final set beyond the number of completed sets.
        val playedSets = (1..5).mapNotNull { setNumber ->
            val array = statsRoot.optJSONArray("set$setNumber") ?: return@mapNotNull null
            if (array.length() == 0) null else rowsFromJson(array)
        }
        return parseStatsData(wholeMatchRows, playedSets, firstName, secondName)
    }

    private fun rowsFromJson(array: JSONArray): List<StatRow> =
        (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            StatRow(
                name = row.optString("name"),
                player1 = row.optString("player1"),
                player2 = row.optString("player2"),
            )
        }

    /**
     * Full-match aggregate wins to avoid double-counting sets. If the aggregate is absent or
     * incomplete, sum every non-empty played-set array, including a partial retirement set.
     */
    internal fun parseStatsData(
        wholeMatchRows: List<StatRow>?,
        playedSets: List<List<StatRow>>,
        firstName: String = "Player 1",
        secondName: String = "Player 2",
    ): AtpMatchStats? {
        if (wholeMatchRows != null) {
            parseStatsRows(wholeMatchRows, firstName, secondName)?.let { return it }
        }
        return aggregateSetStatsRows(playedSets, firstName, secondName)
    }

    /** Pure row parser: independently testable without Android's mocked org.json classes. */
    internal fun parseStatsRows(
        rows: List<StatRow>,
        firstName: String = "Player 1",
        secondName: String = "Player 2",
    ): AtpMatchStats? {
        val aces = rows.firstOrNull { it.name.trim().equals("aces", ignoreCase = true) }
            ?: return null
        val servicePoints = rows.firstOrNull {
            it.name.trim().equals("service points won", ignoreCase = true) ||
                it.name.trim().equals("service points", ignoreCase = true)
        } ?: return null

        val firstAces = aces.player1.trim().toIntOrNull() ?: return null
        val secondAces = aces.player2.trim().toIntOrNull() ?: return null
        val firstPoints = denominator(servicePoints.player1) ?: return null
        val secondPoints = denominator(servicePoints.player2) ?: return null
        if (firstPoints <= 0 || secondPoints <= 0) return null
        // Aces are a subset of service points. Reject malformed/incompatible feed rows.
        if (firstAces < 0 || secondAces < 0 ||
            firstAces > firstPoints || secondAces > secondPoints
        ) return null

        return AtpMatchStats(
            firstPlayer = firstName,
            secondPlayer = secondName,
            firstAces = firstAces,
            firstServePoints = firstPoints,
            secondAces = secondAces,
            secondServePoints = secondPoints,
            firstWon = false,
        )
    }

    /**
     * Aggregate per-set statistics when the ATP feed omits set0 (seen for some retirements).
     * Every included set must contain both Aces and Service Points Won for both players.
     */
    internal fun aggregateSetStatsRows(
        sets: List<List<StatRow>>,
        firstName: String = "Player 1",
        secondName: String = "Player 2",
    ): AtpMatchStats? {
        if (sets.isEmpty()) return null
        var firstAces = 0
        var secondAces = 0
        var firstPoints = 0
        var secondPoints = 0
        var includedSets = 0

        for (rows in sets) {
            val aces = rows.firstOrNull { it.name.trim().equals("aces", ignoreCase = true) }
                ?: return null
            val servicePoints = rows.firstOrNull {
                it.name.trim().equals("service points won", ignoreCase = true) ||
                    it.name.trim().equals("service points", ignoreCase = true)
            } ?: return null

            val a1 = aces.player1.trim().toIntOrNull() ?: return null
            val a2 = aces.player2.trim().toIntOrNull() ?: return null
            val p1 = denominator(servicePoints.player1) ?: return null
            val p2 = denominator(servicePoints.player2) ?: return null
            if (p1 <= 0 || p2 <= 0 || a1 < 0 || a2 < 0 || a1 > p1 || a2 > p2) return null

            firstAces += a1
            secondAces += a2
            firstPoints += p1
            secondPoints += p2
            includedSets++
        }

        if (includedSets == 0) return null
        // The aggregate must remain physically possible after summing all played sets.
        if (firstAces > firstPoints || secondAces > secondPoints) return null
        return AtpMatchStats(
            firstPlayer = firstName,
            secondPlayer = secondName,
            firstAces = firstAces,
            firstServePoints = firstPoints,
            secondAces = secondAces,
            secondServePoints = secondPoints,
            firstWon = false,
        )
    }

    /**
     * Stats Plus formats this as service-points-won / total-service-points (possibly followed by
     * a percentage). Keep the denominator for the rating, but validate the ratio itself.
     */
    private fun denominator(value: String): Int? {
        val ratio = value.trim().substringBefore(" ")
        val parts = ratio.split("/")
        if (parts.size != 2) return null
        val won = parts[0].toIntOrNull() ?: return null
        val total = parts[1].toIntOrNull() ?: return null
        if (won < 0 || total <= 0 || won > total) return null
        return total
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_HTTP_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MS = 2_000L
        const val MAX_RETRY_DELAY_MS = 10_000L
        const val MAX_ERROR_BODY_CHARS = 120
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/47.0.2526.106 Safari/537.36"
    }
}
