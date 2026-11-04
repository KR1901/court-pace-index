package ink.courtpace.index.core

import ink.courtpace.index.core.live.AtpResultsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture is real output from the ATP results page for Paris, 4 November 2026, fetched through
 * the reader this project uses. The shape - tag-stripped lines - is what the app sees after
 * toText(), so the parser is tested against genuine content rather than an invented sample.
 */
class AtpResultsParserTest {

    private val page = """
        ATP Masters 1000 Paris | Results | ATP Tour | Tennis
        ### Rolex Paris Masters
        France | 2-8 Nov, 2026
        #### Wed, 04 November, 2026 Day (5)
        Round of 32 - Court 2 01:59:19
        Tomas Martin Etcheverry
        7 6
        Alex De Minaur (8)
        6 2 3
        Ump: Manuel Messina H2H Stats
        Game Set and Match Tomas Martin Etcheverry. Tomas Martin Etcheverry wins the match 7-6(2) 6-3 .
        Round of 32 - Court Central 01:40:19
        Lorenzo Musetti
        7 6
        Alexander Zverev (1)
        6 5 2
        Ump: Renaud Lichtenstein H2H Stats
        Game Set and Match Lorenzo Musetti. Lorenzo Musetti wins the match 7-6(5) 6-2 .
        Round of 32 - Court Central 01:57:56
        Quentin Halys
        6 1 7
        Frances Tiafoe (7)
        3 6 6 9
        Ump: Manuel Messina H2H Stats
        Game Set and Match Quentin Halys. Quentin Halys wins the match 6-3 1-6 7-6(9).
        Round of 32 - Court 2 00:48:35
        Tommy Paul (12)
        6 6
        Daniel Merida
        1 1
        Ump: Alexandre Robein H2H Stats
        Game Set and Match Tommy Paul. Tommy Paul wins the match 6-1 6-1 .
        Round of 32 - Court 1 02:03:16
        Luca Van Assche
        7 6
        Learner Tien (15)
        6 4 4
        Ump: Louise Azemar Engzell H2H Stats
        Game Set and Match Luca Van Assche. Luca Van Assche wins the match 7-6(4) 6-4 .
        Round of 32 - Court 1 01:05:11
        Arthur Rinderknech
        6 6
        Mattia Bellucci (Q)
        4 1
        Ump: Alexander Franke H2H Stats
        Game Set and Match Arthur Rinderknech. Arthur Rinderknech wins the match 6-4 6-1 .
        Round of 64 - Court Central 01:39:55
        Arthur Rinderknech
        3 7 2
        Francisco Cerundolo (16)
        6 6 2 0
        Ump: Louise Azemar Engzell H2H Stats
        Game Set and Match Arthur Rinderknech. Arthur Rinderknech wins the match 6-3 7-6(2) 2-0 RET.
    """.trimIndent()

    @Test
    fun `parses every completed match with its round`() {
        val matches = AtpResultsParser.parse(page)
        assertEquals(7, matches.size)
        assertEquals("R32", matches.first().round)
        assertEquals("R64", matches.last().round)
        assertEquals("Lorenzo Musetti", matches[1].winner)
    }

    @Test
    fun `reads sets, games and tie-breaks from the score`() {
        val matches = AtpResultsParser.parse(page)
        val halys = matches.first { it.winner == "Quentin Halys" }
        assertEquals(3, halys.sets)
        assertEquals(9 + 7 + 13, halys.games)
        assertEquals(1, halys.tiebreakSets)
        assertEquals("6-3 1-6 7-6(9)", halys.score)

        val paul = matches.first { it.winner == "Tommy Paul" }
        assertEquals(0, paul.tiebreakSets)
        assertEquals(14, paul.games)
    }

    @Test
    fun `a retirement does not corrupt the score`() {
        val ret = AtpResultsParser.parse(page).last()
        assertEquals(3, ret.sets)
        assertEquals(1, ret.tiebreakSets)
        assertTrue(ret.score.endsWith("2-0"))
    }

    @Test
    fun `live pace is derived from the matches, not invented`() {
        val pace = AtpResultsParser.pace(AtpResultsParser.parse(page))!!
        assertEquals(7, pace.matches)
        // Sets: 2+2+3+2+2+2+3 = 16. Tie-break sets: 1+1+1+0+1+0+1 = 5.
        // Games: 22+21+29+14+23+17+24 = 150.
        assertEquals(16, pace.sets)
        assertEquals(5.0 / 16.0, pace.tiebreakShare, 1e-9)
        assertEquals(150.0 / 16.0, pace.gamesPerSet, 1e-9)
    }

    @Test
    fun `an empty page yields no pace rather than a zero`() {
        assertNull(AtpResultsParser.pace(AtpResultsParser.parse("no matches here")))
    }

    @Test
    fun `inline tags do not split the result sentence`() {
        // Real pages wrap fragments of the sentence in spans; the first version of toText put a
        // newline after every tag, which broke the sentence and matched nothing.
        val html = """
            <div class="match"><div class="round">Round of 32 - Court 2 01:59:19</div>
            <span class="w">Tomas Martin Etcheverry</span><span class="s">7 6</span>
            <span class="l">Alex De Minaur (8)</span><span class="s">6 2 3</span>
            <div class="status">Game Set and Match <span>Tomas Martin Etcheverry</span>.
            <span>Tomas Martin Etcheverry</span> wins the match <span>7-6(2)</span> <span>6-3</span> .</div></div>
        """.trimIndent()
        val matches = AtpResultsParser.parse(AtpResultsParser.toText(html))
        assertEquals(1, matches.size)
        assertEquals("R32", matches[0].round)
        assertEquals(2, matches[0].sets)
        assertEquals(1, matches[0].tiebreakSets)
        assertEquals("7-6(2) 6-3", matches[0].score)
    }

    @Test
    fun `markup is stripped before parsing`() {
        val html = "<div><span>Game Set and Match A. B. A. B wins the match 6-4 6-4 .</span></div>"
        val text = AtpResultsParser.toText(html)
        assertTrue("tags should be gone", !text.contains("<span>"))
        assertEquals(1, AtpResultsParser.parse(text).size)
    }
}
