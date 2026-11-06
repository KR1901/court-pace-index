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
    fun `tries the discovered stats route and its live archive counterpart`() {
        val archive = AtpMatchLinks.MatchLink("ms019", "archive", 2026, 352)
        val live = AtpMatchLinks.routeVariants(archive)
        assertEquals(listOf("archive", "live"), live.map { it.section })

        val liveFirst = AtpMatchLinks.routeVariants(archive.copy(section = "live"))
        assertEquals(listOf("live", "archive"), liveFirst.map { it.section })
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
