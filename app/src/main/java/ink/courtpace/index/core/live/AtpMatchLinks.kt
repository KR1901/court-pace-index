package ink.courtpace.index.core.live

/**
 * A match-statistics navigation link found on an ATP results page.
 *
 * The href supplies the year, event ID and match ID. The app uses those identifiers to retrieve
 * the actual statistics from Infosys Stats Plus JSON; it does not scrape the linked HTML page.
 */
object AtpMatchLinks {

    enum class ResultStatus {
        UNKNOWN,
        RETIREMENT,
        WALKOVER,
    }

    data class MatchLink(
        val id: String,
        val section: String,
        val year: Int,
        val atpId: Int,
        /** Result status inferred from the same ATP match card that owns this stats link. */
        val resultStatus: ResultStatus = ResultStatus.UNKNOWN,
    )

    // Accept the route families ATP has used for match-stat links; they are parsed only for IDs.
    private val LINK = Regex(
        """(?i)(?:match[-/]?stats|stats[-/]?centre)/(?:((?:live|archive))/)?([0-9]{4})/([0-9]+)/((?:ms|md)[0-9]+)"""
    )

    private val MATCH_DIV = Regex(
        """<div\b[^>]*\bclass\s*=\s*["']([^"']*)["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val WALKOVER_TEXT = Regex(
        """(?i)(?:\bw\s*/\s*o\b|\bw\.?o\.?\b|\bwalk[-\s]?over\b)""",
    )
    private val RETIREMENT_TEXT = Regex(
        """(?i)(?:\bret\.?\b|\bretired\b|\bretirement\b)""",
    )

    /**
     * Every distinct match in page order. Status is extracted from its owning ATP match card,
     * not inferred from the stats response, so a walkover can be excluded even when its Stats
     * Plus JSON endpoint has no record at all.
     */
    fun linksFromResultsPage(source: String): List<MatchLink> {
        val matchCards = MATCH_DIV.findAll(source)
            .filter { marker -> marker.groupValues[1].split(Regex("""\s+""")).any { it == "match" } }
            .map { it.range.first }
            .toList()

        val parsed = LINK.findAll(source).mapNotNull { match ->
            val year = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val atpId = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val cardStart = matchCards.lastOrNull { it <= match.range.first } ?: match.range.first
            val cardEnd = matchCards.firstOrNull { it > match.range.first } ?: source.length
            val cardText = source.substring(cardStart, cardEnd)
                .replace(Regex("""(?is)<script\b.*?</script>|<style\b.*?</style>"""), " ")
                .replace(Regex("""(?s)<[^>]*>"""), " ")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val resultStatus = when {
                WALKOVER_TEXT.containsMatchIn(cardText) -> ResultStatus.WALKOVER
                RETIREMENT_TEXT.containsMatchIn(cardText) -> ResultStatus.RETIREMENT
                else -> ResultStatus.UNKNOWN
            }
            MatchLink(
                id = match.groupValues[4].lowercase(),
                section = match.groupValues[1].ifBlank { "archive" }.lowercase(),
                year = year,
                atpId = atpId,
                resultStatus = resultStatus,
            )
        }.toList()
        return parsed.groupBy { it.id }.values.map { duplicates ->
            duplicates.firstOrNull {
                it.section == "archive" && it.resultStatus != ResultStatus.UNKNOWN
            } ?: duplicates.firstOrNull { it.section == "archive" } ?: duplicates.first()
        }
    }

    /** Compatibility projection used by parser tests and diagnostics. */
    fun fromResultsPage(source: String): List<String> =
        linksFromResultsPage(source).map { it.id }

    /** Singles only: the surface-speed rating is built from singles serving stats. */
    fun singles(ids: List<String>): List<String> = ids.filter { it.startsWith("ms") }

    fun singlesLinks(links: List<MatchLink>): List<MatchLink> =
        links.filter { it.id.startsWith("ms") }

    /**
     * Only archive links represent completed matches. Live links may have partial setStats and
     * must never be mixed into a completed-match event rating.
     */
    fun completedSinglesLinks(links: List<MatchLink>): List<MatchLink> =
        singlesLinks(links).filter { it.section == "archive" }
}
