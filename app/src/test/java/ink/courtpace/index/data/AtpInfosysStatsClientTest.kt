package ink.courtpace.index.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AtpInfosysStatsClientTest {

    private val client = AtpInfosysStatsClient()

    @Test
    fun `extracts both players aces and total service points from set zero`() {
        val payload = JSONObject(
            """
            {
              "players": [
                {"player1Name": "A. De Minaur"},
                {"player1Name": "A. Rublev"}
              ],
              "setStats": {
                "set0": [
                  {"name": "Serve Rating", "player1": "229", "player2": "270"},
                  {"name": "Aces", "player1": "5", "player2": "4"},
                  {"name": "Service Points Won", "player1": "53/96", "player2": "52/79"}
                ]
              }
            }
            """.trimIndent(),
        )

        val stats = client.parseStats(payload)!!

        assertEquals("A. De Minaur", stats.firstPlayer)
        assertEquals("A. Rublev", stats.secondPlayer)
        assertEquals(5, stats.firstAces)
        assertEquals(4, stats.secondAces)
        assertEquals(96, stats.firstServePoints)
        assertEquals(79, stats.secondServePoints)
    }

    @Test
    fun `matches statistic row names without case sensitivity`() {
        val payload = JSONObject(
            """
            {
              "setStats": {
                "set0": [
                  {"name": "ACES", "player1": "7", "player2": "2"},
                  {"name": "service points won", "player1": "71/126", "player2": "77/132"}
                ]
              }
            }
            """.trimIndent(),
        )

        val stats = client.parseStats(payload)!!

        assertEquals(7, stats.firstAces)
        assertEquals(2, stats.secondAces)
        assertEquals(126, stats.firstServePoints)
        assertEquals(132, stats.secondServePoints)
    }

    @Test
    fun `does not rate a payload missing service point denominators`() {
        val payload = JSONObject(
            """
            {"setStats":{"set0":[
              {"name":"Aces","player1":"7","player2":"2"}
            ]}}
            """.trimIndent(),
        )
        assertNull(client.parseStats(payload))
    }

    @Test
    fun `does not mistake set one for the full match aggregate`() {
        val payload = JSONObject(
            """
            {"setStats":{"set1":[
              {"name":"Aces","player1":"7","player2":"2"},
              {"name":"Service Points Won","player1":"71/126","player2":"77/132"}
            ]}}
            """.trimIndent(),
        )
        assertNull(client.parseStats(payload))
    }
}
