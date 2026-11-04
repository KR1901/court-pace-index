package ink.courtpace.index.core

import ink.courtpace.index.ui.glass.TabBarBehaviour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabBarBehaviourTest {

    private val threshold = 30f

    @Test
    fun `scrolling down hides the bar`() {
        assertEquals(true, TabBarBehaviour.next(dy = -40f, thresholdPx = threshold, touchExplorationActive = false))
    }

    @Test
    fun `scrolling up brings it back`() {
        assertEquals(false, TabBarBehaviour.next(dy = 40f, thresholdPx = threshold, touchExplorationActive = false))
    }

    @Test
    fun `drift does not flicker the bar`() {
        assertNull(TabBarBehaviour.next(dy = -10f, thresholdPx = threshold, touchExplorationActive = false))
        assertNull(TabBarBehaviour.next(dy = 10f, thresholdPx = threshold, touchExplorationActive = false))
        assertNull(TabBarBehaviour.next(dy = 0f, thresholdPx = threshold, touchExplorationActive = false))
    }

    @Test
    fun `a screen reader pins the bar in place`() {
        // The bar is navigation; hiding it while touch exploration runs would take it away
        // from the people who need it most.
        assertNull(TabBarBehaviour.next(dy = -500f, thresholdPx = threshold, touchExplorationActive = true))
        assertNull(TabBarBehaviour.next(dy = 500f, thresholdPx = threshold, touchExplorationActive = true))
    }

    @Test
    fun `exactly at the threshold is still drift`() {
        assertNull(TabBarBehaviour.next(dy = -threshold, thresholdPx = threshold, touchExplorationActive = false))
    }
}
