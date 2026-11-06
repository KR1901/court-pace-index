package ink.courtpace.index.data

import android.util.Base64
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

    data class ReadResult(
        val stats: AtpMatchStats?,
        val payloadBytes: Int,
        val endpoint: String?,
        val diagnostic: String?,
    )

    suspend fun read(year: Int, eventId: Int, matchId: String): ReadResult =
        withContext(Dispatchers.IO) {
            val id = matchId.uppercase()
            val endpoints = listOf(
                "https://itp-atp-sls.infosys-platforms.com/prod/api/stats-plus/v1/keystats/" +
                    "year/$year/eventId/$eventId/matchId/$id",
                "https://itp-atp-sls.infosys-platforms.com/static/prod/stats-plus/" +
                    "$year/$eventId/$id/keystats.json",
            )
            val failures = mutableListOf<String>()
            for (endpoint in endpoints) {
                val response = request(endpoint)
                if (response.first == null) {
                    failures += "${endpoint.substringAfter("/prod/")}: ${response.second}"
                    continue
                }
                val raw = response.first!!
                try {
                    val envelope = JSONObject(raw)
                    val payload = if (envelope.has("setStats")) {
                        envelope
                    } else {
                        decryptEnvelope(envelope)
                    }
                    val stats = parseStats(payload)
                    if (stats != null) {
                        return@withContext ReadResult(
                            stats = stats,
                            payloadBytes = raw.toByteArray(StandardCharsets.UTF_8).size,
                            endpoint = endpoint,
                            diagnostic = null,
                        )
                    }
                    failures += "${endpoint.substringAfter("/prod/")}: JSON had no usable setStats.set0 ace/service rows"
                } catch (error: Exception) {
                    failures += "${endpoint.substringAfter("/prod/")}: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(100)}"
                }
            }
            ReadResult(
                stats = null,
                payloadBytes = 0,
                endpoint = null,
                diagnostic = failures.joinToString(" | ").take(280),
            )
        }

    private fun request(url: String): Pair<String?, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Origin", ATP_ORIGIN)
            connection.setRequestProperty("Referer", "$ATP_ORIGIN/")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status in 200..299 && body.isNotBlank()) {
                body to "HTTP $status"
            } else {
                null to "HTTP $status (${body.length} chars)"
            }
        } catch (error: Exception) {
            null to "${error.javaClass.simpleName}: ${error.message.orEmpty().take(100)}"
        } finally {
            connection.disconnect()
        }
    }

    private fun decryptEnvelope(envelope: JSONObject): JSONObject {
        val lastModified = envelope.getLong("lastModified")
        val encoded = envelope.getString("response")
        val keyText = cipherKey(lastModified)
        val key = keyText.toByteArray(StandardCharsets.UTF_8)
        val iv = keyText.uppercase().toByteArray(StandardCharsets.UTF_8)
        require(key.size == 16 && iv.size == 16) { "Invalid ATP cipher key length" }

        val encrypted = Base64.decode(encoded, Base64.DEFAULT)
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
        val json = String(padded, 0, padded.size - padding, StandardCharsets.UTF_8).trim()
        return JSONObject(json)
    }

    /**
     * Port of ATP Infosys' published scraper key derivation:
     * '#' + base36(hex(timestamp)) + base24((year+reverse(year))*(day+reverse(day))) + '$'
     * The middle string is padded/truncated to fourteen characters.
     */
    private fun cipherKey(lastModified: Long): String {
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

    private fun parseStats(payload: JSONObject): AtpMatchStats? {
        val statsRoot = payload.optJSONObject("setStats") ?: return null
        val matchRows = statsRoot.optJSONArray("set0") ?: return null
        var aces: JSONArray? = null
        var servicePoints: JSONArray? = null
        for (i in 0 until matchRows.length()) {
            val row = matchRows.optJSONObject(i) ?: continue
            when (row.optString("name").trim().lowercase()) {
                "aces" -> aces = JSONArray().put(row.opt("player1")).put(row.opt("player2"))
                "service points won", "service points" -> servicePoints =
                    JSONArray().put(row.opt("player1")).put(row.opt("player2"))
            }
        }
        val aceRow = aces ?: return null
        val pointRow = servicePoints ?: return null
        val firstAces = aceRow.optString(0).toIntOrNull() ?: return null
        val secondAces = aceRow.optString(1).toIntOrNull() ?: return null
        val firstPoints = denominator(pointRow.optString(0)) ?: return null
        val secondPoints = denominator(pointRow.optString(1)) ?: return null
        if (firstPoints <= 0 || secondPoints <= 0) return null

        val players = payload.optJSONArray("players")
        val firstName = players?.optJSONObject(0)?.optString("player1Name")
            ?.takeIf { it.isNotBlank() } ?: "Player 1"
        val secondName = players?.optJSONObject(1)?.optString("player1Name")
            ?.takeIf { it.isNotBlank() } ?: "Player 2"

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

    private fun denominator(value: String): Int? {
        val ratio = value.trim().substringBefore(" ")
        val parts = ratio.split("/")
        return if (parts.size == 2) parts[1].toIntOrNull() else null
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val ATP_ORIGIN = "https://www.atptour.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"
    }
}
