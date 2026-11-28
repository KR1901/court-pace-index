package ink.courtpace.index.ui.screens

import ink.courtpace.index.core.model.Tournament
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareSelectionTest {

    @Test
    fun selectedEventsWithoutCurrentMetricReadingRemainVisibleAndRemovable() {
        val priorYearSelection = tournament("miami", "Miami Open")
        val currentYearCandidate = tournament("paris", "Paris Masters")

        val menu = compareMenuEvents(
            selectedIds = listOf("miami"),
            allTournaments = listOf(priorYearSelection, currentYearCandidate),
            activeCandidates = listOf(currentYearCandidate),
        )

        assertEquals(listOf("miami", "paris"), menu.map { it.tournament.id })
        assertFalse(menu.first().hasActiveReading)
        assertTrue(menu.last().hasActiveReading)
    }

    @Test
    fun selectedEventsWithCurrentReadingAreNotDuplicated() {
        val selected = tournament("miami", "Miami Open")
        val other = tournament("paris", "Paris Masters")

        val menu = compareMenuEvents(
            selectedIds = listOf("miami"),
            allTournaments = listOf(selected, other),
            activeCandidates = listOf(selected, other),
        )

        assertEquals(listOf("miami", "paris"), menu.map { it.tournament.id })
        assertEquals(1, menu.count { it.tournament.id == "miami" })
        assertTrue(menu.first().hasActiveReading)
    }

    private fun tournament(id: String, name: String) = Tournament(
        id = id,
        name = name,
        city = "City",
        country = "Country",
    )
}
