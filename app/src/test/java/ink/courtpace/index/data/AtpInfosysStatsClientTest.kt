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
    fun `uses the user agent expected by the established ATP Infosys scraper`() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/47.0.2526.106 Safari/537.36",
            AtpInfosysStatsClient.USER_AGENT,
        )
    }

    @Test
    fun `tries the established static keystats feed before the API fallback`() {
        val endpoints = client.endpointsForMatch(2026, 352, "ms043")

        assertEquals(
            "https://itp-atp-sls.infosys-platforms.com/static/prod/stats-plus/2026/352/MS043/keystats.json",
            endpoints.first(),
        )
        assertEquals(
            "https://itp-atp-sls.infosys-platforms.com/prod/api/stats-plus/v1/keystats/year/2026/eventId/352/matchId/MS043",
            endpoints[1],
        )
    }

    @Test
    fun `classifies match outcomes generically from feed metadata and played-set presence`() {
        assertEquals(
            AtpInfosysStatsClient.MatchDataState.NO_PLAYED_DATA,
            client.classifyMatchData(true, false, 0, false, false),
        )
        assertEquals(
            AtpInfosysStatsClient.MatchDataState.PLAYED,
            client.classifyMatchData(true, false, 2, true, true),
        )
        assertEquals(
            AtpInfosysStatsClient.MatchDataState.INCOMPLETE_STATS,
            client.classifyMatchData(true, false, 2, true, false),
        )
        assertEquals(
            AtpInfosysStatsClient.MatchDataState.IN_PROGRESS,
            client.classifyMatchData(false, false, 0, true, false),
        )
        assertEquals(
            AtpInfosysStatsClient.MatchDataState.DOUBLES,
            client.classifyMatchData(true, true, 2, true, true),
        )
        assertEquals(
            AtpInfosysStatsClient.MatchDataState.INCOMPLETE_STATS,
            client.classifyMatchData(null, false, null, false, false),
        )
    }

    @Test
    fun `uses set zero aggregate instead of double counting the per-set rows`() {
        val whole = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "7", "5"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "47/69", "45/68"),
        )
        val sets = listOf(
            listOf(
                AtpInfosysStatsClient.StatRow("Aces", "4", "3"),
                AtpInfosysStatsClient.StatRow("Service Points Won", "25/38", "24/36"),
            ),
            listOf(
                AtpInfosysStatsClient.StatRow("Aces", "3", "2"),
                AtpInfosysStatsClient.StatRow("Service Points Won", "22/31", "21/32"),
            ),
        )

        val stats = client.parseStatsData(whole, sets)!!

        assertEquals(7, stats.firstAces)
        assertEquals(5, stats.secondAces)
        assertEquals(69, stats.firstServePoints)
        assertEquals(68, stats.secondServePoints)
    }

    @Test
    fun `falls back across every played set including a partially played final set`() {
        val sets = listOf(
            listOf(
                AtpInfosysStatsClient.StatRow("Aces", "3", "2"),
                AtpInfosysStatsClient.StatRow("Service Points Won", "20/30", "25/40"),
            ),
            listOf(
                AtpInfosysStatsClient.StatRow("Aces", "1", "4"),
                AtpInfosysStatsClient.StatRow("Service Points Won", "11/16", "12/18"),
            ),
            listOf(
                AtpInfosysStatsClient.StatRow("Aces", "2", "1"),
                AtpInfosysStatsClient.StatRow("Service Points Won", "8/13", "9/14"),
            ),
        )

        val stats = client.parseStatsData(null, sets)!!

        assertEquals(6, stats.firstAces)
        assertEquals(7, stats.secondAces)
        assertEquals(59, stats.firstServePoints)
        assertEquals(72, stats.secondServePoints)
    }

    @Test
    fun `retries transient feed refusal and server errors but not permanent client errors`() {
        listOf(403, 408, 425, 429, 500, 502, 503, 504).forEach { status ->
            assertEquals(true, client.shouldRetryStatus(status))
        }
        listOf(200, 204, 400, 401, 404, 422).forEach { status ->
            assertEquals(false, client.shouldRetryStatus(status))
        }
    }

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
    fun `parses the user-confirmed ms043 Rinderknech Cerundolo match stats`() {
        val rows = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "7", "5"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "47/69", "45/68"),
        )

        val stats = client.parseStatsRows(rows, "Arthur Rinderknech", "Francisco Cerundolo")!!

        assertEquals("Arthur Rinderknech", stats.firstPlayer)
        assertEquals("Francisco Cerundolo", stats.secondPlayer)
        assertEquals(7, stats.firstAces)
        assertEquals(5, stats.secondAces)
        assertEquals(69, stats.firstServePoints)
        assertEquals(68, stats.secondServePoints)
    }

    @Test
    fun `parses the user-confirmed ms053 Shapovalov Khachanov match stats`() {
        val rows = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "8", "2"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "61/97", "53/84"),
        )

        val stats = client.parseStatsRows(rows, "Denis Shapovalov", "Karen Khachanov")!!

        assertEquals("Denis Shapovalov", stats.firstPlayer)
        assertEquals("Karen Khachanov", stats.secondPlayer)
        assertEquals(8, stats.firstAces)
        assertEquals(2, stats.secondAces)
        assertEquals(97, stats.firstServePoints)
        assertEquals(84, stats.secondServePoints)
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
    fun `rejects a retirement fallback if any played set lacks service point totals`() {
        val played = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "3", "2"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "20/30", "25/40"),
        )
        val incomplete = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "1", "4"),
        )

        assertNull(client.aggregateSetStatsRows(listOf(played, incomplete)))
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
    fun `rejects aces greater than a players total service points`() {
        val rows = listOf(
            AtpInfosysStatsClient.StatRow("Aces", "8", "2"),
            AtpInfosysStatsClient.StatRow("Service Points Won", "4/7", "5/20"),
        )
        assertNull(client.parseStatsRows(rows))
    }

    @Test
    fun `retirement with no completed set count still remains played when partial set stats exist`() {
        assertEquals(
            AtpInfosysStatsClient.MatchDataState.INCOMPLETE_STATS,
            client.classifyMatchData(
                matchCompleted = true,
                isDoubles = false,
                setsCompleted = 0,
                hasPlayedSetData = true,
                statsAvailable = false,
            ),
        )
        assertEquals(
            AtpInfosysStatsClient.MatchDataState.PLAYED,
            client.classifyMatchData(
                matchCompleted = true,
                isDoubles = false,
                setsCompleted = 0,
                hasPlayedSetData = true,
                statsAvailable = true,
            ),
        )
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
