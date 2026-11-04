package ink.courtpace.index.ui.glass

/**
 * When the floating tab bar should step out of the way.
 *
 * Both current platform guides describe the same rule for a bar that floats over scrolling
 * content: it minimises as the content scrolls down and comes back when the user scrolls up.
 * Android additionally requires the bar to stay put while a touch-exploration service such as
 * TalkBack is running, because then the bar is navigation the user cannot afford to lose.
 */
object TabBarBehaviour {

    /** Movement below this is drift, not intent, and must not flicker the bar. */
    const val DEFAULT_THRESHOLD_DP = 12f

    /**
     * @param dy vertical scroll delta in pixels: negative when the content moves up (the user
     *   is scrolling down the list), positive when it moves down.
     * @return true to hide, false to show, null to leave the bar as it is.
     */
    fun next(dy: Float, thresholdPx: Float, touchExplorationActive: Boolean): Boolean? = when {
        touchExplorationActive -> null
        dy < -thresholdPx -> true
        dy > thresholdPx -> false
        else -> null
    }
}
