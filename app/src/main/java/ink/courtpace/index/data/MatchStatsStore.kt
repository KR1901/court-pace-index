package ink.courtpace.index.data

import android.content.Context
import ink.courtpace.index.core.live.AtpMatchStats

/**
 * Per-match stats already read, kept so a refresh only fetches what is new.
 *
 * The first run of the day has to read every page; every run after it should be near-instant,
 * and a match that has been read once never changes.
 */
class MatchStatsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("match-stats", Context.MODE_PRIVATE)

    fun readAll(eventKey: String): Map<String, AtpMatchStats> =
        decodeStatsCache(prefs.getString(eventKey, null).orEmpty())


    /**
     * Confirmed no-play outcomes (normally walkovers) can be cached separately from stats.
     * This prevents re-requesting a Stats Plus record that correctly has no service data on
     * every five-minute refresh. The set is always intersected with the current draw by caller.
     */
    fun readNoPlayIds(eventKey: String): Set<String> =
        prefs.getStringSet("${eventKey}:no-play", emptySet())?.toSet().orEmpty()

    fun writeNoPlayIds(eventKey: String, matchIds: Set<String>) {
        prefs.edit().putStringSet("${eventKey}:no-play", matchIds.toSet()).apply()
    }

    fun writeAll(eventKey: String, stats: Map<String, AtpMatchStats>) {
        prefs.edit().putString(eventKey, encodeStatsCache(stats)).apply()
    }

    companion object {
        private val nameEncoder = java.util.Base64.getUrlEncoder().withoutPadding()
        private val nameDecoder = java.util.Base64.getUrlDecoder()

        /**
         * Cache the player identities as well as the numeric stats. Opponent adjustment needs
         * both names to look up the server's serve rate and the returner's concession rate.
         */
        internal fun encodeStatsCache(stats: Map<String, AtpMatchStats>): String =
            stats.entries
                .filter { (_, match) ->
                    isUsablePlayerName(match.firstPlayer) && isUsablePlayerName(match.secondPlayer)
                }
                .joinToString("\n") { (id, match) ->
                    listOf(
                        id,
                        encodeName(match.firstPlayer),
                        encodeName(match.secondPlayer),
                        match.firstAces.toString(),
                        match.firstServePoints.toString(),
                        match.secondAces.toString(),
                        match.secondServePoints.toString(),
                        if (match.firstWon) "1" else "0",
                    ).joinToString("|")
                }

        /**
         * Old cache rows stored only five numbers and reconstructed both players as "cached".
         * They cannot be opponent-adjusted. Reject them so AtpLiveClient refetches those matches
         * instead of silently counting their two server sides as unmatched.
         */
        internal fun decodeStatsCache(raw: String): Map<String, AtpMatchStats> =
            raw.lineSequence().mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size != 8) return@mapNotNull null
                val firstPlayer = decodeName(parts[1]) ?: return@mapNotNull null
                val secondPlayer = decodeName(parts[2]) ?: return@mapNotNull null
                if (!isUsablePlayerName(firstPlayer) || !isUsablePlayerName(secondPlayer)) {
                    return@mapNotNull null
                }
                val firstAces = parts[3].toIntOrNull() ?: return@mapNotNull null
                val firstPoints = parts[4].toIntOrNull() ?: return@mapNotNull null
                val secondAces = parts[5].toIntOrNull() ?: return@mapNotNull null
                val secondPoints = parts[6].toIntOrNull() ?: return@mapNotNull null
                val firstWon = when (parts[7]) {
                    "1" -> true
                    "0" -> false
                    else -> return@mapNotNull null
                }
                if (firstAces < 0 || secondAces < 0 ||
                    firstPoints <= 0 || secondPoints <= 0 ||
                    firstAces > firstPoints || secondAces > secondPoints
                ) return@mapNotNull null

                parts[0] to AtpMatchStats(
                    firstPlayer = firstPlayer,
                    secondPlayer = secondPlayer,
                    firstAces = firstAces,
                    firstServePoints = firstPoints,
                    secondAces = secondAces,
                    secondServePoints = secondPoints,
                    firstWon = firstWon,
                    matchId = parts[0],
                )
            }.toMap()

        private fun encodeName(value: String): String =
            nameEncoder.encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decodeName(value: String): String? = runCatching {
            String(nameDecoder.decode(value), Charsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()

        private fun isUsablePlayerName(value: String): Boolean {
            val clean = value.trim()
            if (clean.isBlank()) return false
            if (clean.equals("cached", ignoreCase = true)) return false
            if (clean.equals("Player 1", ignoreCase = true)) return false
            if (clean.equals("Player 2", ignoreCase = true)) return false
            return true
        }
    }
}
