package ink.courtpace.index.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AtpInfosysStatsClientTest {

    private val client = AtpInfosysStatsClient()


    @Test
    fun `derives the documented ATP Infosys AES key for a known timestamp`() {
        assertEquals("#3zfbzn55vkkiln$", client.cipherKey(1_700_000_000_000L))
    }

    @Test
    fun `decrypts an AES CBC Infosys response envelope with PKCS7 padding`() {
        val timestamp = 1_700_000_000_000L
        val clearText = """{"setStats":{"set0":[]}}"""
        val clearBytes = clearText.toByteArray(StandardCharsets.UTF_8)
        val padding = 16 - (clearBytes.size % 16)
        val padded = clearBytes + ByteArray(padding) { padding.toByte() }

        val keyText = client.cipherKey(timestamp)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyText.toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec(keyText.uppercase().toByteArray(StandardCharsets.UTF_8)),
        )
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(padded))

        assertEquals(clearText, client.decryptResponse(timestamp, encoded))
    }

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
    fun `aggregates played sets when a retired match has no set zero total`() {
        val set1 = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "3", "2"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "20/30", "25/40"),
        )
        val set2 = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "1", "4"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "11/16", "12/18"),
        )

        val stats = client.aggregateSetStatsRows(
            listOf(set1, set2),
            "A. Rinderknech",
            "F. Cerundolo",
        )!!

        assertEquals(4, stats.firstAces)
        assertEquals(6, stats.secondAces)
        assertEquals(46, stats.firstServePoints)
        assertEquals(58, stats.secondServePoints)
    }

    @Test
    fun `does not count a set that lacks service point totals`() {
        val played = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "3", "2"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "20/30", "25/40"),
        )
        val incomplete = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "1", "4"),
        )

        val stats = client.aggregateSetStatsRows(listOf(played, incomplete))!!

        assertEquals(3, stats.firstAces)
        assertEquals(2, stats.secondAces)
        assertEquals(30, stats.firstServePoints)
        assertEquals(40, stats.secondServePoints)
    }

    @Test
    fun `returns no match stats when every played set is a walkover or has no points`() {
        assertNull(client.aggregateSetStatsRows(emptyList()))
        assertNull(
            client.aggregateSetStatsRows(
                listOf(listOf(AtpInfosysStatsClient.StatRow("Aces", "0", "0"))),
            ),
        )
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
