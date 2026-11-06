package ink.courtpace.index.core.live

/**
 * Serve statistics from one ATP "Stats Centre" match page.
 *
 * The ATP stats view populates its numbers client-side. This parser consumes the text snapshot
 * returned by the device WebView, so markup classes do not matter and player headings are not
 * required for the event-level ace-rate calculation.
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

    // ATP has rendered both "7 Aces 2 Aces" and "Aces 7 Aces 2" across page versions.
    private val ACES_BEFORE_LABELS = Regex(
        """([0-9]+)\s*Aces\s*([0-9]+)\s*Aces""",
        RegexOption.IGNORE_CASE,
    )
    private val LABELS_BEFORE_ACES = Regex(
        """Aces\s*([0-9]+)\s*Aces\s*([0-9]+)""",
        RegexOption.IGNORE_CASE,
    )
    // The denominator is total service points, not first-serve points won.
    private val FIRST_SERVE = Regex(
        """(?:([0-9]+)/([0-9]+)\s*\([0-9]+%\)\s*First serve|First serve\s*([0-9]+)/([0-9]+)\s*\([0-9]+%\))""",
        RegexOption.IGNORE_CASE,
    )
    // Total service points are also printed explicitly on ATP's stats cards.
    private val SERVICE_POINTS = Regex(
        """(?:([0-9]+)/([0-9]+)\s*\([0-9]+%\)\s*Service Points Won|Service Points Won\s*([0-9]+)/([0-9]+)\s*\([0-9]+%\))""",
        RegexOption.IGNORE_CASE,
    )
    // "F. Comesana" - the two short names that flank the score.
    private val SHORT_NAME = Regex("""^[A-Z]\.[\s\u00a0]+[A-Za-z\u00c0-\u024f'\- ]{2,30}$""")
    private val WINNER = Regex("""([A-Z][^.]{2,40}?)\s+wins the match""", RegexOption.IGNORE_CASE)

    /** True when the page carried numbers rather than the empty JavaScript template. */
    private fun acePair(text: String): Pair<Int, Int>? {
        val match = ACES_BEFORE_LABELS.find(text) ?: LABELS_BEFORE_ACES.find(text)
            ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull() ?: return null
        return first to second
    }

    fun hasStats(text: String): Boolean = acePair(text) != null

    fun parse(text: String): AtpMatchStats? {
        val (firstAces, secondAces) = acePair(text) ?: return null
        // First-serve attempts' denominator is total service points. If ATP changes the
        // order/name of that field, fall back to its explicit Service Points Won row, whose
        // denominator is the same total.
        val serves = FIRST_SERVE.findAll(text).toList().takeIf { it.size >= 2 }
            ?: SERVICE_POINTS.findAll(text).toList().takeIf { it.size >= 2 }
            ?: return null

        // The full player-name headings are cosmetic for this metric. Some ATP page renders omit
        // the abbreviated names, while the ace and serve totals remain sufficient to rate a match.
        val names = text.lines().map { it.trim() }.filter { SHORT_NAME.matches(it) }
        val firstPlayer = names.getOrNull(0) ?: "Player 1"
        val secondPlayer = names.getOrNull(1) ?: "Player 2"

        // Both supported ATP layouts preserve made/attempted; the second value is total service
        // points (first-serve plus second-serve attempts).
        fun servicePoints(match: MatchResult): Int? =
            (match.groups[2]?.value ?: match.groups[4]?.value)?.toIntOrNull()

        val firstPoints = servicePoints(serves[0]) ?: return null
        val secondPoints = servicePoints(serves[1]) ?: return null
        if (firstPoints <= 0 || secondPoints <= 0) return null

        val winner = WINNER.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val firstSurname = firstPlayer.substringAfter(". ", "").trim()
        val firstWon = firstSurname.isNotEmpty() &&
            winner.isNotEmpty() && winner.contains(firstSurname, ignoreCase = true)

        return AtpMatchStats(
            firstPlayer = firstPlayer,
            secondPlayer = secondPlayer,
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

    data class Result(
        val rating: Double,
        /** All successfully parsed, completed singles matches included in the input. */
        val matches: Int,
        val aces: Int,
        /** Pooled descriptive ace rate; the rating itself is opponent-adjusted. */
        val aceRate: Double,
        val adjustedServerSides: Int,
        val discoveredMatches: Int = matches,
        val missingMatchIds: List<String> = emptyList(),
        val missingReasons: Map<String, String> = emptyMap(),
        val inProgressMatches: Int = 0,
        val walkoversExcluded: Int = 0,
    )

    /** Preserve the historical pipeline's minimum event sample: twenty server-side observations. */
    const val MIN_MATCHES = 8
    const val MIN_ADJUSTED_SERVER_SIDES = 20

    private fun normaliseName(value: String): String {
        val decomposed = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
    }

    /**
     * Same opponent-adjusted calculation as tools/compute_speed.py:
     * expected aces = ((serveRate * concedeRate) /
     * (serveRate * concedeRate + (1-serveRate) * (1-concedeRate))) * servicePoints.
     * Average actual/expected ratios over both server sides, then divide by the season-wide
     * mean of event raw scores. Missing player profiles are omitted, never replaced by the
     * pooled-rate approximation.
     */
    fun rate(matches: List<AtpMatchStats>, baseline: ink.courtpace.index.core.model.LiveSpeedBaseline): Result? {
        if (matches.size < MIN_MATCHES || baseline.normalisation <= 0.0 ||
            !baseline.normalisation.isFinite()
        ) return null

        val profiles = baseline.profiles.associateBy { normaliseName(it.name) }
        var totalAces = 0
        var totalPoints = 0
        val ratios = mutableListOf<Double>()

        fun addSide(serverName: String, returnerName: String, aces: Int, servicePoints: Int) {
            totalAces += aces
            totalPoints += servicePoints
            val server = profiles[normaliseName(serverName)] ?: return
            val returner = profiles[normaliseName(returnerName)] ?: return
            val serveRate = server.serveRate ?: return
            val concedeRate = returner.concedeRate ?: return
            if (servicePoints <= 0 || aces < 0 || aces > servicePoints) return
            val joint = serveRate * concedeRate
            val denominator = joint + (1.0 - serveRate) * (1.0 - concedeRate)
            if (denominator <= 0.0) return
            val expected = (joint / denominator) * servicePoints
            if (expected <= 0.5) return
            ratios += aces.toDouble() / expected
        }

        matches.forEach { match ->
            addSide(match.firstPlayer, match.secondPlayer, match.firstAces, match.firstServePoints)
            addSide(match.secondPlayer, match.firstPlayer, match.secondAces, match.secondServePoints)
        }

        if (totalPoints <= 0 || ratios.size < MIN_ADJUSTED_SERVER_SIDES) return null
        val rawEventScore = ratios.average()
        val pooledAceRate = totalAces.toDouble() / totalPoints
        return Result(
            rating = rawEventScore / baseline.normalisation,
            matches = matches.size,
            aces = totalAces,
            aceRate = pooledAceRate,
            adjustedServerSides = ratios.size,
        )
    }
}
