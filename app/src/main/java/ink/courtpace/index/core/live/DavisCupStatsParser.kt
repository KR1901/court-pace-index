package ink.courtpace.index.core.live

/**
 * Parses a completed Davis Cup singles match page from TennisRace.ai.
 * Only pages with full player names, aces and total service-point denominators are accepted.
 */
object DavisCupStatsParser {

    private val matchup = Regex(
        """(?m)^\s*#?\s*([A-Za-zÀ-ÿ'’. -]{3,60}?)\s+vs\s+([A-Za-zÀ-ÿ'’. -]{3,60}?)\s*$""",
    )
    private val completedDuration = Regex("""\b(?:[0-9]+h(?:\s+[0-9]+m)?|[0-9]+m)\b""", RegexOption.IGNORE_CASE)
    private val inProgressMarker = Regex(
        """(?im)^\s*(?:live|in progress|scheduled|not started)\s*$""",
    )
    private val acesAfterLabel = Regex(
        """(?is)\bAces\s*[:：]?\s*([0-9]+)\s+([0-9]+)\b""",
    )
    private val servicePointsAfterLabel = Regex(
        """(?is)Service points won\s*[:：]?\s*[0-9]+%\s*[0-9]+\s*/\s*([0-9]+)\s+[0-9]+%\s*[0-9]+\s*/\s*([0-9]+)""",
    )

    fun parse(text: String): AtpMatchStats? {
        if (text.isBlank() || inProgressMarker.containsMatchIn(text)) return null
        if (!completedDuration.containsMatchIn(text)) return null

        val names = matchup.find(text) ?: return null
        val aces = acesAfterLabel.find(text) ?: return null
        val points = servicePointsAfterLabel.find(text) ?: return null

        val firstPlayer = names.groupValues[1].trim()
        val secondPlayer = names.groupValues[2].trim()
        val firstAces = aces.groupValues[1].toIntOrNull() ?: return null
        val secondAces = aces.groupValues[2].toIntOrNull() ?: return null
        val firstPoints = points.groupValues[1].toIntOrNull() ?: return null
        val secondPoints = points.groupValues[2].toIntOrNull() ?: return null

        if (firstPlayer.isBlank() || secondPlayer.isBlank() ||
            firstAces < 0 || secondAces < 0 ||
            firstPoints <= 0 || secondPoints <= 0 ||
            firstAces > firstPoints || secondAces > secondPoints
        ) return null

        return AtpMatchStats(
            firstPlayer = firstPlayer,
            secondPlayer = secondPlayer,
            firstAces = firstAces,
            firstServePoints = firstPoints,
            secondAces = secondAces,
            secondServePoints = secondPoints,
            firstWon = false,
        )
    }
}
