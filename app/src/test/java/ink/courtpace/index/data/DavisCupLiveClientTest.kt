package ink.courtpace.index.data

import ink.courtpace.index.data.DavisCupLiveClient.Companion.matchUrlsFromSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DavisCupLiveClientTest {

    @Test
    fun onlyDavisCupFinalEightMatchLinksAreDiscoveredFromLatestResults() {
        val html = """
            <a href="https://www.tennisrace.ai/match/5338523-tomas-machac-vs-liam-draxl-davis-cup-final-8-atp-2026">Stats: Machac, Draxl</a>
            <a href="/match/5338524-jiri-lehecka-vs-felix-auger-aliassime-davis-cup-final-8-atp-2026">Stats: Lehecka, Auger-Aliassime</a>
            <a href="/match/1111111-player-a-vs-player-b-atp-finals-2026">ATP Finals stats</a>
            <a href="/davis-cup-2026/czechia-vs-canada">Davis Cup tie page</a>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://www.tennisrace.ai/match/5338523-tomas-machac-vs-liam-draxl-davis-cup-final-8-atp-2026",
                "https://www.tennisrace.ai/match/5338524-jiri-lehecka-vs-felix-auger-aliassime-davis-cup-final-8-atp-2026",
            ),
            matchUrlsFromSource(html),
        )
    }
}
