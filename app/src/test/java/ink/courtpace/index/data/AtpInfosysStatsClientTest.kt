package ink.courtpace.index.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AtpInfosysStatsClientTest {

    private val client = AtpInfosysStatsClient()

    @Test
    fun `extracts both players aces and total service points from set zero rows`() {
        val rows = listOf(
            AtpInfosysStatsClient.StatRow("Serve Rating", "229", "270"),
            AtpInfosysStatsClient.StatRow("Aces", "5", "4"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "53/96", "52/79"),
        )

        val stats = client.parseStatsRows(rows, "A. De Minaur", "A. Rublev")!!

        assertEquals("A. De Minaur", stats.firstPlayer)
        assertEquals("A. Rublev", stats.secondPlayer)
        assertEquals(5, stats.firstAces)
        assertEquals(4, stats.secondAces)
        assertEquals(96, stats.firstServePoints)
        assertEquals(79, stats.secondServePoints)
    }

    @Test
    fun `matches statistic row names without case sensitivity`() {
        val rows = listOf(
            AtpInfosysStatsClient.StatRow("ACES", "7", "2"),
            AtpInfosysStatsClient.StatRow("service points won", "71/126", "77/132"),
        )

        val stats = client.parseStatsRows(rows)!!

        assertEquals(7, stats.firstAces)
        assertEquals(2, stats.secondAces)
        assertEquals(126, stats.firstServePoints)
        assertEquals(132, stats.secondServePoints)
    }

    @Test
    fun `does not rate rows missing service point denominators`() {
        val rows = listOf(AtpInfosysStatsClient.StatRow("Aces", "7", "2"))
        assertNull(client.parseStatsRows(rows))
    }

    @Test
    fun `requires a full-match service aggregate row`() {
        val rows = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "7", "2"),
            AtpInfosysStatsClient.StatRow("1st Serve", "35/48 (73%)", "41/55 (75%)"),
        )
        assertNull(client.parseStatsRows(rows))
    }

    @Test
    fun `rejects non-positive service point totals`() {
        val rows = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "7", "2"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "4/0", "5/0"),
        )
        assertNull(client.parseStatsRows(rows))
    }
}
