package ink.courtpace.index.data

import ink.courtpace.index.core.live.AtpMatchStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchStatsStoreTest {

    @Test
    fun `round trips both player identities and match statistics`() {
        val expected = mapOf(
            "ms043" to AtpMatchStats(
                firstPlayer = "Arthur Rinderknech",
                secondPlayer = "Francisco Cerundolo",
                firstAces = 7,
                firstServePoints = 69,
                secondAces = 5,
                secondServePoints = 68,
                firstWon = true,
            ),
        )

        val encoded = MatchStatsStore.encodeStatsCache(expected)
        val decoded = MatchStatsStore.decodeStatsCache(encoded)

        assertEquals(expected, decoded)
    }

    @Test
    fun `rejects legacy numeric-only cache rows so live client refetches player identities`() {
        // Previous cache format stored only numbers and restored both names as "cached".
        val legacy = "ms043|7|69|5|68|1||"

        assertTrue(MatchStatsStore.decodeStatsCache(legacy).isEmpty())
    }

    @Test
    fun `does not persist placeholder player names that cannot be opponent adjusted`() {
        val stats = mapOf(
            "ms043" to AtpMatchStats(
                firstPlayer = "Player 1",
                secondPlayer = "Player 2",
                firstAces = 7,
                firstServePoints = 69,
                secondAces = 5,
                secondServePoints = 68,
                firstWon = true,
            ),
            "ms044" to AtpMatchStats(
                firstPlayer = "cached",
                secondPlayer = "Francisco Cerundolo",
                firstAces = 7,
                firstServePoints = 69,
                secondAces = 5,
                secondServePoints = 68,
                firstWon = true,
            ),
        )

        assertEquals("", MatchStatsStore.encodeStatsCache(stats))
    }
}
