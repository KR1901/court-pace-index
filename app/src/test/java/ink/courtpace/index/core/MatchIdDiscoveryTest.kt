package ink.courtpace.index.core

import ink.courtpace.index.core.live.AtpMatchLinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ATP results page is the source of truth for match IDs. The parser keeps archive/live
 * sections distinct so a completed match uses its archived stats page, never a guessed ID range.
 */
class MatchIdDiscoveryTest {

    private val html = """
        <div class="match">
          <a href="/en/scores/stats-centre/archive/2026/352/ms019">H2H Stats</a>
          <a href="/en/scores/stats-centre/archive/2026/352/ms030">H2H Stats</a>
          <a href="/en/scores/stats-centre/live/2026/352/ms031">H2H Stats</a>
          <a href="/en/scores/stats-centre/archive/2026/352/ms031">H2H Stats</a>
          <a href="/en/scores/stats-centre/archive/2026/352/md006">H2H Stats</a>
          <a href="/en/scores/current/paris/352/results">Results</a>
        </div>
    """.trimIndent()

    @Test
    fun `reads the match ids out of the results page`() {
        val ids = AtpMatchLinks.fromResultsPage(html)
        assertEquals(listOf("ms019", "ms030", "ms031", "md006"), ids)
    }

    @Test
    fun `completed singles links exclude in-progress live routes`() {
        val links = AtpMatchLinks.linksFromResultsPage(
            """
            <a href="/en/scores/stats-centre/archive/2026/352/ms001">completed</a>
            <a href="/en/scores/stats-centre/live/2026/352/ms002">in progress</a>
            <a href="/en/scores/stats-centre/archive/2026/352/md003">doubles</a>
            """.trimIndent(),
        )

        val completed = AtpMatchLinks.completedSinglesLinks(links)

        assertEquals(listOf("ms001"), completed.map { it.id })
    }

    @Test
    fun `keeps confirmed Paris retirement IDs in the completed singles set`() {
        val links = AtpMatchLinks.linksFromResultsPage(
            """
            <a href="/en/scores/stats-centre/archive/2026/352/ms043">Rinderknech Cerundolo</a>
            <a href="/en/scores/stats-centre/archive/2026/352/ms053">Shapovalov Khachanov</a>
            <a href="/en/scores/stats-centre/live/2026/352/ms054">live match</a>
            <a href="/en/scores/stats-centre/archive/2026/352/md043">doubles</a>
            """.trimIndent(),
        )

        val completedIds = AtpMatchLinks.completedSinglesLinks(links).map { it.id }

        assertEquals(listOf("ms043", "ms053"), completedIds)
    }

    @Test
    fun `classifies walkovers and retirements from each ATP match card without match-specific rules`() {
        val html = """
            <div class="match">
              <div class="result-status">W/O</div>
              <div class="match-cta"><a href="/en/scores/stats-centre/archive/2026/352/ms060">Stats</a></div>
            </div>
            <div class="match">
              <div class="result-status">Ret.</div>
              <div class="match-cta"><a href="/en/scores/stats-centre/archive/2026/352/ms061">Stats</a></div>
            </div>
            <div class="match">
              <div class="match-cta"><a href="/en/scores/stats-centre/archive/2026/352/ms062">Stats</a></div>
            </div>
            <div class="match">
              <div class="result-status">W.O.</div>
              <div class="match-cta"><a href="/en/scores/stats-centre/archive/2026/352/ms063">Stats</a></div>
            </div>
            <div class="match">
              <div class="result-status">Walk-over</div>
              <div class="match-cta"><a href="/en/scores/stats-centre/archive/2026/352/ms064">Stats</a></div>
            </div>
        """.trimIndent()

        val links = AtpMatchLinks.linksFromResultsPage(html).associateBy { it.id }

        assertEquals(AtpMatchLinks.ResultStatus.WALKOVER, links.getValue("ms060").resultStatus)
        assertEquals(AtpMatchLinks.ResultStatus.RETIREMENT, links.getValue("ms061").resultStatus)
        assertEquals(AtpMatchLinks.ResultStatus.UNKNOWN, links.getValue("ms062").resultStatus)
        assertEquals(AtpMatchLinks.ResultStatus.WALKOVER, links.getValue("ms063").resultStatus)
        assertEquals(AtpMatchLinks.ResultStatus.WALKOVER, links.getValue("ms064").resultStatus)
    }

    @Test
    fun `recognises ATP match-stats URLs as well as legacy stats-centre links`() {
        val links = AtpMatchLinks.linksFromResultsPage(
            """<a href="/en/scores/match-stats/archive/2026/352/ms041">Match stats</a>""",
        )
        assertEquals(1, links.size)
        assertEquals("ms041", links.single().id)
        assertEquals("archive", links.single().section)
    }

    @Test
    fun `an id linked twice is listed once`() {
        val ids = AtpMatchLinks.fromResultsPage(html)
        assertEquals(ids.size, ids.distinct().size)
    }



    @Test
    fun `discovers the complete 55-match main draw from sparse ATP IDs`() {
        // Real ATP match IDs are sparse; the reader must follow links, not assume ms001..ms055.
        val ids = (1..70).filter { it % 5 != 0 }.take(55).map { "ms%03d".format(it) }
        val html = buildString {
            ids.forEach { id ->
                append("""<a href="/en/scores/stats-centre/archive/2026/352/$id">stats</a>""")
            }
            // Noise that must not inflate the completed-singles count.
            append("""<a href="/en/scores/stats-centre/live/2026/352/${ids.first()}">live</a>""")
            append("""<a href="/en/scores/stats-centre/archive/2026/352/md001">doubles</a>""")
            append("""<a href="/en/scores/stats-centre/archive/2026/400/ms900">another event</a>""")
        }

        val completedSingles = AtpMatchLinks.singlesLinks(
            AtpMatchLinks.linksFromResultsPage(html),
        ).filter { it.atpId == 352 && it.year == 2026 && it.section == "archive" }

        assertEquals(55, completedSingles.size)
        assertEquals(ids, completedSingles.map { it.id })
    }

    @Test
    fun `a page with no stats links yields nothing rather than inventing ids`() {
        assertTrue(AtpMatchLinks.fromResultsPage("<div>no links here</div>").isEmpty())
        assertTrue(AtpMatchLinks.fromResultsPage("").isEmpty())
    }
}
