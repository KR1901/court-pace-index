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

    fun readAll(eventKey: String): Map<String, AtpMatchStats> {
        val raw = prefs.getString(eventKey, null) ?: return emptyMap()
        return raw.split('\n')
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 8) return@mapNotNull null
                val id = parts[0]
                val numbers = parts.drop(1).mapNotNull { it.toIntOrNull() }
                if (numbers.size != 5) return@mapNotNull null
                id to AtpMatchStats(
                    firstPlayer = "cached",
                    secondPlayer = "cached",
                    firstAces = numbers[0],
                    firstServePoints = numbers[1],
                    secondAces = numbers[2],
                    secondServePoints = numbers[3],
                    firstWon = numbers[4] == 1,
                )
            }
            .toMap()
    }

    fun writeAll(eventKey: String, stats: Map<String, AtpMatchStats>) {
        val text = stats.entries.joinToString("\n") { (id, s) ->
            listOf(
                id,
                s.firstAces.toString(),
                s.firstServePoints.toString(),
                s.secondAces.toString(),
                s.secondServePoints.toString(),
                if (s.firstWon) "1" else "0",
                "", "",
            ).joinToString("|")
        }
        prefs.edit().putString(eventKey, text).apply()
    }
}
