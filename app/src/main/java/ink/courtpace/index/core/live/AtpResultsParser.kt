package ink.courtpace.index.core.live

/**
 * A completed match as the ATP public results page publishes it.
 *
 * The page is server-rendered, so a plain HTTPS GET returns it. It carries the round, both
 * players, the final score and the duration. It does *not* carry ace counts: those live behind
 * the site's JavaScript stats view, which a plain GET cannot see.
 */
data class AtpMatch(
    val round: String,
    val winner: String,
    val loser: String,
    val score: String,
    val sets: Int,
    val games: Int,
    val tiebreakSets: Int,
) {
    val gamesPerSet: Double get() = if (sets > 0) games.toDouble() / sets else 0.0
}

/**
 * Reads the ATP results page.
 *
 * Parsing works on tag-stripped text rather than the markup: the page's class names change,
 * but the sentences it renders - "X wins the match 7-6(2) 6-3" - do not. That keeps the parser
 * stable against a redesign of the surrounding HTML.
 */
object AtpResultsParser {

    private val ROUND = Regex("""Round of (\d+)""")
    private val RESULT = Regex(
        """wins the match\s+((?:[0-9]+-[0-9]+(?:\(\d+\))?\s*)+)""",
        RegexOption.IGNORE_CASE,
    )
    private val SET = Regex("""(\d+)-(\d+)(?:\((\d+)\))?""")

    /** Round markers and results, in document order, so line structure cannot break the walk. */
    private val TOKEN = Regex(
        """(?i)Round of (\d+)|wins the match\s+((?:[0-9]+-[0-9]+(?:\(\d+\))?\s*)+)""",
    )

    /**
     * Tags out, script and style bodies dropped.
     *
     * Only block-level closers become line breaks. Replacing *every* tag with a newline - the
     * first version of this - split sentences like "X wins the match 7-6(2) 6-3" across several
     * lines wherever the page used an inline span, and nothing matched. Inline tags now vanish
     * without inserting a break.
     */
    fun toText(html: String): String =
        html
            .replace(Regex("""(?is)<script.*?</script>"""), " ")
            .replace(Regex("""(?is)<style.*?</style>"""), " ")
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(div|p|li|tr|h[1-6]|section|article|td)>"""), "\n")
            .replace(Regex("""(?s)<[^>]+>"""), "")
            .replace(Regex("""&nbsp;?"""), " ")
            .replace(Regex("""&amp;"""), "&")
            .replace(Regex("""&#39;"""), "'")
            .replace(Regex("""[ \t\u00a0]+"""), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /**
     * Walks the text once, remembering the most recent "Round of N" marker so each result line
     * is attributed to its round. A result line always names the winner first.
     */
    fun parse(text: String): List<AtpMatch> {
        val matches = mutableListOf<AtpMatch>()
        var round = "Unknown"
        for (token in TOKEN.findAll(text)) {
            val roundValue = token.groups[1]
            if (roundValue != null) {
                round = "R" + roundValue.value
                continue
            }
            val score = token.groups[2]?.value?.trim() ?: continue
            val sets = SET.findAll(score).toList()
            if (sets.isEmpty()) continue
            // The clause reads "Game Set and Match X. X wins the match ..."; the winner is the
            // last sentence fragment before it.
            val start = maxOf(0, token.range.first - 200)
            val winner = text.substring(start, token.range.first)
                .substringAfterLast('.')
                .trim()
                .trim('>', ' ')
            val games = sets.sumOf { it.groupValues[1].toInt() + it.groupValues[2].toInt() }
            val tiebreaks = sets.count { it.groupValues[3].isNotEmpty() }
            matches += AtpMatch(
                round = round,
                winner = winner,
                loser = "",
                score = score,
                sets = sets.size,
                games = games,
                tiebreakSets = tiebreaks,
            )
        }
        return matches
    }

    /** Share of sets that reached a tie-break, and games per set, across the completed matches. */
    data class LivePace(
        val matches: Int,
        val sets: Int,
        val tiebreakShare: Double,
        val gamesPerSet: Double,
    )

    fun pace(matches: List<AtpMatch>): LivePace? {
        if (matches.isEmpty()) return null
        val sets = matches.sumOf { it.sets }
        if (sets == 0) return null
        val tiebreaks = matches.sumOf { it.tiebreakSets }
        val games = matches.sumOf { it.games }
        return LivePace(
            matches = matches.size,
            sets = sets,
            tiebreakShare = tiebreaks.toDouble() / sets,
            gamesPerSet = games.toDouble() / sets,
        )
    }
}
