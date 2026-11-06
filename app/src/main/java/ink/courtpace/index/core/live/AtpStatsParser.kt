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
    /** ATP match identifier, attached by the live client and preserved in its stats cache. */
    val matchId: String? = null,
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
 * Live Surface Speed uses the same opponent-adjusted, season-normalised method as historical
 * Speed in tools/compute_speed.py. The bundled season baseline supplies player serve and return
 * tendencies plus the season mean of raw event scores; ATP/Infosys supplies current event stats.
 * Missing player profiles are excluded from adjusted server-side observations, never replaced
 * with the old pooled ace-rate approximation.
 */
object AceRating {

    data class Result(
        val rating: Double,
        /** All successfully parsed, completed singles matches included in the input. */
        val matches: Int,
        val aces: Int,
        /** Pooled descriptive ace rate; the rating itself is opponent-adjusted. */
        val aceRate: Double,
        /** Server-side observations actually used in the opponent-adjusted mean. */
        val adjustedServerSides: Int,
        /**
         * Possible server sides across all eligible completed singles matches discovered
         * from ATP. AtpLiveClient replaces the default with discoveredMatches * 2, so
         * missing Stats Plus payloads remain visible in the denominator.
         */
        val totalServerSides: Int = matches * 2,
        val discoveredMatches: Int = matches,
        val missingMatchIds: List<String> = emptyList(),
        val missingReasons: Map<String, String> = emptyMap(),
        /** Why each player-server side was excluded from the opponent-adjusted mean. */
        val adjustmentIssues: List<AdjustmentIssue> = emptyList(),
        val inProgressMatches: Int = 0,
        val walkoversExcluded: Int = 0,
    )

    /** Preserve the historical pipeline's minimum event sample: twenty server-side observations. */
    const val MIN_MATCHES = 8
    const val MIN_ADJUSTED_SERVER_SIDES = 20
    const val MIN_EXPECTED_ACES = 0.0
    const val EXPECTED_ACES_FLOOR = 0.5

    private fun normaliseName(value: String): String {
        val decomposed = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
    }

    /**
     * ATP Stats Plus commonly identifies a player as "A. DE MINAUR", while the historical
     * archive stores "Alex de Minaur". Match on the first initial plus the complete surname,
     * not on the literal display string. Names with multiple initials (for example
     * "J. M. CERUNDOLO") are supported too.
     */
    private fun abbreviatedNameAlias(value: String): String? {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val initials = StringBuilder()
        var index = 0
        while (index < parts.size) {
            val token = parts[index].trimEnd('.')
            if (token.length == 1) {
                initials.append(token)
                index++
            } else {
                break
            }
        }
        if (initials.isEmpty() || index >= parts.size) return null
        return normaliseName(initials.toString() + parts.drop(index).joinToString(""))
            .takeIf { it.isNotBlank() }
    }

    private data class ProfileIndex(
        val exact: Map<String, ink.courtpace.index.core.model.LiveSpeedPlayerProfile>,
        val aliases: Map<String, List<ink.courtpace.index.core.model.LiveSpeedPlayerProfile>>,
        val tokenAliases: Map<String, List<ink.courtpace.index.core.model.LiveSpeedPlayerProfile>>,
    ) {
        fun resolve(displayName: String): ink.courtpace.index.core.model.LiveSpeedPlayerProfile? {
            val key = normaliseName(displayName)
            exact[key]?.let { return it }

            val abbreviated = abbreviatedNameAlias(displayName)
            if (abbreviated != null) {
                // A short-name collision must not silently assign one player's rates to another.
                return aliases[abbreviated]?.singleOrNull()
            }

            // Infosys sometimes supplies the first name plus a shortened surname
            // (for example "Daniel Merida"), while the ATP archive stores the full
            // multi-part name ("Daniel Merida Aguilar"). Match contiguous name tokens,
            // but only when the resulting profile is unique.
            val tokenAlias = tokenSequenceAlias(displayName) ?: return null
            return tokenAliases[tokenAlias]?.singleOrNull()
        }
    }

    private fun tokenSequenceAlias(value: String): String? {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts.joinToString("") { normaliseName(it) }.takeIf { it.isNotBlank() }
    }

    private fun profileIndex(
        profiles: List<ink.courtpace.index.core.model.LiveSpeedPlayerProfile>,
    ): ProfileIndex {
        val exact = profiles.associateBy { normaliseName(it.name) }
        val aliases = linkedMapOf<String, MutableList<ink.courtpace.index.core.model.LiveSpeedPlayerProfile>>()
        val tokenAliases = linkedMapOf<String, MutableList<ink.courtpace.index.core.model.LiveSpeedPlayerProfile>>()

        fun addAlias(alias: String, profile: ink.courtpace.index.core.model.LiveSpeedPlayerProfile) {
            if (alias.isBlank()) return
            val matches = aliases.getOrPut(alias) { mutableListOf() }
            if (matches.none { normaliseName(it.name) == normaliseName(profile.name) }) {
                matches += profile
            }
        }

        profiles.forEach { profile ->
            val parts = profile.name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size < 2) return@forEach

            // Index each contiguous multi-token span from a full archive name. This safely
            // handles ATP's "Daniel Merida" label for "Daniel Merida Aguilar"; collisions
            // remain unresolved instead of selecting an arbitrary player's profile.
            for (start in parts.indices) {
                for (end in start + 1 until parts.size) {
                    val key = parts.subList(start, end + 1)
                        .joinToString("") { normaliseName(it) }
                    val matches = tokenAliases.getOrPut(key) { mutableListOf() }
                    if (matches.none { normaliseName(it.name) == normaliseName(profile.name) }) {
                        matches += profile
                    }
                }
            }

            /*
             * ATP score/result labels can shorten compound names in more than one way:
             *   Alex de Minaur          -> A. DE MINAUR
             *   Adolfo Daniel Vallejo   -> D. VALLEJO
             *   Daniel Merida Aguilar   -> D. MERIDA
             *   Juan Manuel Cerundolo   -> J. M. CERUNDOLO
             *
             * Generate aliases from any contiguous initials followed by any contiguous
             * name-token sequence. Resolution below accepts only a unique alias, so the
             * broader coverage cannot silently bind an ambiguous short name to a player.
             */
            for (initialStart in 0 until parts.lastIndex) {
                for (initialEnd in initialStart until parts.lastIndex) {
                    val initials = parts.subList(initialStart, initialEnd + 1)
                        .joinToString("") { it.take(1) }
                    for (suffixStart in initialEnd + 1 until parts.size) {
                        for (suffixEnd in suffixStart until parts.size) {
                            val suffix = parts.subList(suffixStart, suffixEnd + 1).joinToString("")
                            addAlias(normaliseName(initials + suffix), profile)
                        }
                    }
                }
            }
        }

        return ProfileIndex(
            exact = exact,
            aliases = aliases.mapValues { (_, values) -> values.toList() },
            tokenAliases = tokenAliases.mapValues { (_, values) -> values.toList() },
        )
    }

    data class AdjustmentIssue(
        val matchId: String?,
        val server: String,
        val returner: String,
        val reason: String,
    )

    data class Coverage(
        val totalServerSides: Int,
        val serverProfilesMatched: Int,
        val returnerProfilesMatched: Int,
        val adjustedServerSides: Int,
        val issues: List<AdjustmentIssue> = emptyList(),
    )

    private data class Evaluation(
        val totalAces: Int,
        val totalPoints: Int,
        val ratios: List<Double>,
        val coverage: Coverage,
    )

    private fun evaluate(
        matches: List<AtpMatchStats>,
        baseline: ink.courtpace.index.core.model.LiveSpeedBaseline,
    ): Evaluation {
        val totalSides = matches.size * 2
        if (baseline.normalisation <= 0.0 || !baseline.normalisation.isFinite()) {
            return Evaluation(0, 0, emptyList(), Coverage(totalSides, 0, 0, 0))
        }

        val profiles = profileIndex(baseline.profiles)
        var totalAces = 0
        var totalPoints = 0
        var serverProfilesMatched = 0
        var returnerProfilesMatched = 0
        val ratios = mutableListOf<Double>()
        val issues = mutableListOf<AdjustmentIssue>()

        fun addSide(serverName: String, returnerName: String, aces: Int, servicePoints: Int, matchId: String?) {
            totalAces += aces
            totalPoints += servicePoints

            val server = profiles.resolve(serverName)
            val returner = profiles.resolve(returnerName)
            val serveRate = server?.serveRate
            val concedeRate = returner?.concedeRate

            if (serveRate != null) serverProfilesMatched++
            if (concedeRate != null) returnerProfilesMatched++

            fun exclude(reason: String) {
                issues += AdjustmentIssue(
                    matchId = matchId,
                    server = serverName,
                    returner = returnerName,
                    reason = reason,
                )
            }

            if (serveRate == null && concedeRate == null) {
                exclude("no server serve profile or returner concession profile")
                return
            }
            if (serveRate == null) {
                exclude("no server serve profile")
                return
            }
            if (concedeRate == null) {
                exclude("no returner concession profile")
                return
            }
            if (servicePoints <= 0 || aces < 0 || aces > servicePoints) {
                exclude("invalid ace/service-point statistics")
                return
            }
            val joint = serveRate * concedeRate
            val denominator = joint + (1.0 - serveRate) * (1.0 - concedeRate)
            if (!denominator.isFinite() || denominator <= 0.0) {
                exclude("invalid expected-aces denominator")
                return
            }
            val expected = (joint / denominator) * servicePoints
            if (!expected.isFinite() || expected <= MIN_EXPECTED_ACES) {
                exclude("zero or invalid expected aces (server=$serveRate, returner=$concedeRate)")
                return
            }
            // Match the historical pipeline: retain every valid positive-expectation side.
            // Floor expected aces at 0.5 to avoid unstable ratios without discarding a player side.
            ratios += aces.toDouble() / maxOf(expected, EXPECTED_ACES_FLOOR)
        }

        matches.forEach { match ->
            addSide(match.firstPlayer, match.secondPlayer, match.firstAces, match.firstServePoints, match.matchId)
            addSide(match.secondPlayer, match.firstPlayer, match.secondAces, match.secondServePoints, match.matchId)
        }

        return Evaluation(
            totalAces = totalAces,
            totalPoints = totalPoints,
            ratios = ratios,
            coverage = Coverage(
                totalServerSides = totalSides,
                serverProfilesMatched = serverProfilesMatched,
                returnerProfilesMatched = returnerProfilesMatched,
                adjustedServerSides = ratios.size,
                issues = issues.toList(),
            ),
        )
    }

    /** Explain how much of the supplied match sample can be opponent-adjusted. */
    fun coverage(
        matches: List<AtpMatchStats>,
        baseline: ink.courtpace.index.core.model.LiveSpeedBaseline,
    ): Coverage = evaluate(matches, baseline).coverage

    /**
     * Same opponent-adjusted calculation as tools/compute_speed.py:
     * expected aces = ((serveRate * concedeRate) /
     * (serveRate * concedeRate + (1-serveRate) * (1-concedeRate))) * servicePoints.
     * Expected-ace counts below 0.5 are floored at 0.5 rather than excluded, so valid low-ace
     * player sides remain covered without allowing tiny expectations to dominate. Average
     * actual/expected ratios over both server sides, then divide by the season-wide mean of
     * historical event raw scores. Missing player profiles are omitted, never replaced
     * by the pooled-rate approximation.
     */
    fun rate(
        matches: List<AtpMatchStats>,
        baseline: ink.courtpace.index.core.model.LiveSpeedBaseline,
    ): Result? {
        if (matches.size < MIN_MATCHES) return null
        val evaluation = evaluate(matches, baseline)
        if (evaluation.totalPoints <= 0 ||
            evaluation.ratios.size < MIN_ADJUSTED_SERVER_SIDES
        ) return null

        val rawEventScore = evaluation.ratios.average()
        val pooledAceRate = evaluation.totalAces.toDouble() / evaluation.totalPoints
        return Result(
            rating = rawEventScore / baseline.normalisation,
            matches = matches.size,
            aces = evaluation.totalAces,
            aceRate = pooledAceRate,
            adjustedServerSides = evaluation.coverage.adjustedServerSides,
            totalServerSides = evaluation.coverage.totalServerSides,
            adjustmentIssues = evaluation.coverage.issues,
        )
    }

}
