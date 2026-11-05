package ink.courtpace.index.core.live

/**
 * Serve statistics from one ATP "Stats Centre" match page.
 *
 * Those pages are server-rendered - a plain GET returns the numbers - unlike the tournament
 * results page, which carries no aces at all. The parser reads the flattened text, so class
 * names and markup changes do not matter.
 */
data class AtpMatchStats(
    val firstPlayer: String,
    val secondPlayer: String,
    val firstAces: Int,
    val firstServePoints: Int,
    val secondAces: Int,
    val secondServePoints: Int,
    val firstWon: Boolean,
)

object AtpStatsParser {

    private val ACES = Regex("""([0-9]+)\s*Aces\s*([0-9]+)\s*Aces""")
    private val FIRST_SERVE = Regex("""([0-9]+)/([0-9]+)\s*\([0-9]+%\)\s*First serve""")
    // "F. Comesana" - the two short names that flank the score.
    private val SHORT_NAME = Regex("""^[A-Z]\.[\s\u00a0]+[A-Za-z\u00c0-\u024f'\- ]{2,30}$""")
    private val WINNER = Regex("""([A-Z][^.]{2,40}?)\s+wins the match""", RegexOption.IGNORE_CASE)

    /** True when the page carried numbers rather than the empty JavaScript template. */
    fun hasStats(text: String): Boolean = ACES.containsMatchIn(text)

    fun parse(text: String): AtpMatchStats? {
        val aces = ACES.find(text) ?: return null
        val serves = FIRST_SERVE.findAll(text).toList()
        if (serves.size < 2) return null

        val names = text.lines().map { it.trim() }.filter { SHORT_NAME.matches(it) }
        if (names.size < 2) return null

        val firstAces = aces.groupValues[1].toIntOrNull() ?: return null
        val secondAces = aces.groupValues[2].toIntOrNull() ?: return null
        // The denominator of "first serve made/attempted" is that player's total service
        // points: first-serve attempts plus second-serve attempts.
        val firstPoints = serves[0].groupValues[2].toIntOrNull() ?: return null
        val secondPoints = serves[1].groupValues[2].toIntOrNull() ?: return null
        if (firstPoints <= 0 || secondPoints <= 0) return null

        val winner = WINNER.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val firstSurname = names[0].substringAfter(". ").trim()
        val firstWon = winner.isNotEmpty() && winner.contains(firstSurname, ignoreCase = true)

        return AtpMatchStats(
            firstPlayer = names[0],
            secondPlayer = names[1],
            firstAces = firstAces,
            firstServePoints = firstPoints,
            secondAces = secondAces,
            secondServePoints = secondPoints,
            firstWon = firstWon,
        )
    }
}

/**
 * Ace-based surface speed for an event in progress.
 *
 * This is the app's documented construction - ace rate against a tour-average surface - applied
 * to the matches completed so far. The tour average is 7.83% of service points, measured over
 * 10,834 tour-level matches (2022-2025, 137,134 aces from 1,751,112 service points) in the
 * public archive, so 1.00 is the tour and anything above it is faster.
 *
 * An earlier version of this used the odds-ratio expectation from the historical pipeline. It
 * is not used here because it is not monotonic in ace rate: as both sides' rates rise the
 * expected count rises faster than the observed one, so a big-serving event scored *lower*.
 * A plain rate ratio moves the way the number claims to move.
 */
object AceRating {

    data class Result(val rating: Double, val matches: Int, val aces: Int, val aceRate: Double)

    /** Tour-level ace rate, 2022-2025. */
    const val BASELINE_ACE_RATE = 0.078313

    /** Fewer matches than this and the figure says more about the draw than the court. */
    const val MIN_MATCHES = 8

    fun rate(matches: List<AtpMatchStats>): Result? {
        if (matches.size < MIN_MATCHES) return null
        val aces = matches.sumOf { it.firstAces + it.secondAces }
        val points = matches.sumOf { it.firstServePoints + it.secondServePoints }
        if (points <= 0) return null
        val aceRate = aces.toDouble() / points
        return Result(
            rating = aceRate / BASELINE_ACE_RATE,
            matches = matches.size,
            aces = aces,
            aceRate = aceRate,
        )
    }
}
