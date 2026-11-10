package ink.courtpace.index.core

import ink.courtpace.index.core.live.AtpEvents
import ink.courtpace.index.core.model.Level
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.Surface
import ink.courtpace.index.core.model.Tournament
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AtpEventsTest {

    private val tournaments = listOf(
        Tournament("paris", "Paris Masters", "Paris", "FRA", Surface.HARD, true, Level.M1000),
        Tournament("indianwells", "Indian Wells Masters", "Indian Wells", "USA", Surface.HARD, false, Level.M1000),
        Tournament("canada", "Canadian Open", "Toronto", "CAN", Surface.HARD, false, Level.M1000),
        Tournament("brisbane", "Brisbane International", "Brisbane", "AUS", Surface.HARD, false, Level.ATP250),
        Tournament("rolandgarros", "Roland Garros", "Paris", "FRA", Surface.CLAY, false, Level.GS),
        Tournament("nextgen", "Next Gen Finals", "Jeddah", "SAU", Surface.HARD, true, Level.SPECIAL),
    )

    @Test
    fun `discovers several current tour events from one score hub snapshot`() {
        val page = """
            <a href="https://www.atptour.com/en/scores/current/paris/352/results">Paris</a>
            <a href="/en/scores/current/indian-wells/404/results">Indian Wells</a>
            <a href="/en/scores/current/toronto/421/results">Canada</a>
            <a href="/en/scores/current/brisbane/990/results">Brisbane</a>
            <a href="/en/scores/current/roland-garros/520/results">Roland Garros</a>
            <a href="/en/scores/current/next-gen-finals/999/results">Next Gen</a>
        """.trimIndent()

        val found = AtpEvents.fromCurrentScoresPage(page, tournaments, 2026)

        assertEquals(listOf("paris", "indianwells", "canada", "brisbane"), found.map { it.tournamentId })
        assertEquals(listOf(352, 404, 421, 990), found.map { it.atpId })
        assertTrue(found.all { it.year == 2026 })
    }

    @Test
    fun `ignores unknown routes and non target tournament levels`() {
        val page = """
            /en/scores/current/roland-garros/520/results
            /en/scores/current/next-gen-finals/999/results
            /en/scores/current/not-in-our-dataset/1001/results
        """.trimIndent()

        assertTrue(AtpEvents.fromCurrentScoresPage(page, tournaments, 2026).isEmpty())
    }

    @Test
    fun `an expired bundled event is not kept live when hub discovery is empty`() {
        val stale = LiveEvent(
            t = "paris",
            s = 2026,
            status = "in_progress",
            end = LocalDate.now().minusDays(1).toString(),
        )
        assertTrue(AtpEvents.fallback(listOf(stale), 2026).isEmpty())
    }

    @Test
    fun `a still-running bundled event remains a fallback`() {
        val active = LiveEvent(
            t = "paris",
            s = 2026,
            status = "in_progress",
            end = LocalDate.now().plusDays(1).toString(),
        )
        assertEquals("paris", AtpEvents.fallback(listOf(active), 2026).single().tournamentId)
    }

    @Test
    fun `legacy live event metadata remains a fallback when the hub is unavailable`() {
        val ref = AtpEvents.forTournament("paris")!!
        assertEquals("paris", ref.tournamentId)
        assertEquals(352, ref.atpId)
        assertEquals("paris", ref.slug)
    }
}
