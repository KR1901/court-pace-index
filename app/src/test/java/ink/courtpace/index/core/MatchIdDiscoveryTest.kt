package ink.courtpace.index.core

import ink.courtpace.index.core.live.AtpMatchLinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The results page links to every match's stats page, so those hrefs are the authoritative list
 * of matches. Walking a guessed id range found fourteen of them out of ninety-five attempts.
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
    fun `an id linked twice is listed once`() {
        val ids = AtpMatchLinks.fromResultsPage(html)
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `a page with no stats links yields nothing rather than inventing ids`() {
        assertTrue(AtpMatchLinks.fromResultsPage("<div>no links here</div>").isEmpty())
        assertTrue(AtpMatchLinks.fromResultsPage("").isEmpty())
    }
}
