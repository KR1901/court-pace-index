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
        Tournament("stockholm", "Stockholm Open", "Stockholm", "SWE", Surface.HARD, true, Level.ATP250),
        Tournament("atpfinals", "ATP Finals", "Turin", "ITA", Surface.HARD, true, Level.FINALS),
    )

    @Test
    fun `discovers several current tour events from one score hub snapshot`() {
        val page = """
            <a href="https://www.atptour.com/en/scores/current/paris/352/results">Paris</a>
            <a href="/en/scores/current/indian-wells/404/results">Indian Wells</a>
            <a href="/en/scores/current/toronto/421/results">Canada</a>
            <a href="/en/scores/current/brisbane/990/results">Brisbane</a>
            <a href="/en/scores/current/roland-garros/520/results">Roland Garros</a>
            <a href="/en/scores/current/nitto-atp-finals/605/results">ATP Finals</a>
            <a href="/en/scores/current/next-gen-finals/999/results">Next Gen</a>
        """.trimIndent()

        val found = AtpEvents.fromCurrentScoresPage(page, tournaments, 2026)

        assertEquals(
            listOf("paris", "indianwells", "canada", "brisbane", "rolandgarros", "atpfinals"),
            found.map { it.tournamentId },
        )
        assertEquals(listOf(352, 404, 421, 990, 520, 605), found.map { it.atpId })
        assertTrue(found.all { it.year == 2026 })
    }

    @Test
    fun `current hub does not revive an ended event beside an active one`() {
        val page = """
            <a href="/en/scores/current/paris/352/results">Paris Masters</a>
            <a href="/en/scores/current/stockholm/429/results">Stockholm Open</a>
        """.trimIndent()
        val schedule = listOf(
            LiveEvent(
                t = "paris", s = 2026, status = "in_progress",
                start = "2026-11-02", end = "2026-11-08",
            ),
            LiveEvent(
                t = "stockholm", s = 2026, status = "in_progress",
                start = "2026-11-08", end = "2026-11-14",
            ),
        )

        val found = AtpEvents.fromCurrentScoresPage(
            source = page,
            tournaments = tournaments,
            year = 2026,
            liveSchedule = schedule,
            today = LocalDate.of(2026, 11, 9),
        )

        assertEquals(listOf("stockholm"), found.map { it.tournamentId })
        assertEquals(429, found.single().atpId)
    }

    @Test
    fun `a terminal schedule tombstone rejects a stale ATP route without date bounds`() {
        val page = """<a href="/en/scores/current/paris/352/results">Paris Masters</a>"""
        val completed = LiveEvent(
            t = "paris",
            s = 2026,
            status = "completed",
        )

        val found = AtpEvents.fromCurrentScoresPage(
            source = page,
            tournaments = tournaments,
            year = 2026,
            liveSchedule = listOf(completed),
            today = LocalDate.of(2026, 11, 10),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun `ignores unknown routes and non target tournament levels`() {
        val page = """
            /en/scores/current/next-gen-finals/999/results
            /en/scores/current/not-in-our-dataset/1001/results
        """.trimIndent()

        assertTrue(AtpEvents.fromCurrentScoresPage(page, tournaments, 2026).isEmpty())
    }

    @Test
    fun `completed events use their explicit season archive results route`() {
        val stock = AtpEvents.Ref(
            tournamentId = "stockholm",
            atpId = 429,
            slug = "stockholm",
            year = 2026,
            completed = true,
        )
        assertEquals(
            "https://www.atptour.com/en/scores/archive/stockholm/429/2026/results",
            AtpEvents.resultsUrl(stock),
        )
    }

    @Test
    fun `active ATP Finals uses the current results route`() {
        val finals = AtpEvents.Ref(
            tournamentId = "atpfinals",
            atpId = 605,
            slug = "nitto-atp-finals",
            year = 2026,
        )
        assertEquals(
            "https://www.atptour.com/en/scores/current/nitto-atp-finals/605/results",
            AtpEvents.resultsUrl(finals),
        )
    }

    @Test
    fun `recent completed Stockholm is queued for rating but excluded from active fallback`() {
        val stock = LiveEvent(
            t = "stockholm", s = 2026, status = "completed",
            start = "2026-11-08", end = "2026-11-14",
            atpId = 429, slug = "stockholm",
        )
        val older = stock.copy(t = "paris", end = "2026-11-07", atpId = 352, slug = "paris")
        val refs = AtpEvents.recentCompletedFromSchedule(
            liveSchedule = listOf(stock, older),
            tournaments = tournaments,
            year = 2026,
            today = LocalDate.of(2026, 11, 15),
        )
        assertEquals(listOf("stockholm"), refs.map { it.tournamentId })
        assertTrue(AtpEvents.fallback(listOf(stock), 2026).isEmpty())
    }

    @Test
    fun `ATP Finals is included when the readable scores hub has not listed its route yet`() {
        val scheduled = LiveEvent(
            t = "atpfinals", s = 2026, status = "scheduled",
            start = "2026-11-15", end = "2026-11-22",
            atpId = 605, slug = "nitto-atp-finals",
        )
        val refs = AtpEvents.scheduledFromSchedule(
            liveSchedule = listOf(scheduled),
            tournaments = tournaments,
            year = 2026,
            today = LocalDate.of(2026, 11, 15),
        )
        assertEquals("atpfinals", refs.single().tournamentId)
    }

    @Test
    fun `ATP Finals route is discovered as a standard live tour event`() {
        val found = AtpEvents.fromCurrentScoresPage(
            source = """/en/scores/current/nitto-atp-finals/605/results""",
            tournaments = tournaments,
            year = 2026,
        )
        assertEquals("atpfinals", found.single().tournamentId)
        assertEquals(605, found.single().atpId)
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
