package ink.courtpace.index.core.live

/**
 * A match-statistics navigation link found on an ATP results page.
 *
 * The href supplies the year, event ID and match ID. The app uses those identifiers to retrieve
 * the actual statistics from Infosys Stats Plus JSON; it does not scrape the linked HTML page.
 */
object AtpMatchLinks {

    data class MatchLink(
        val id: String,
        val section: String,
        val year: Int,
        val atpId: Int,
    )

    // Accept the route families ATP has used for match-stat links; they are parsed only for IDs.
    private val LINK = Regex(
        """(?i)(?:match[-/]?stats|stats[-/]?centre)/(?:((?:live|archive))/)?([0-9]{4})/([0-9]+)/((?:ms|md)[0-9]+)"""
    )

    /** Every distinct match in page order; prefer an archive link if the same id appears twice. */
    fun linksFromResultsPage(source: String): List<MatchLink> {
        val parsed = LINK.findAll(source).mapNotNull { match ->
            val year = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val atpId = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            MatchLink(
                id = match.groupValues[4].lowercase(),
                section = match.groupValues[1].ifBlank { "archive" }.lowercase(),
                year = year,
                atpId = atpId,
            )
        }.toList()
        return parsed.groupBy { it.id }.values.map { duplicates ->
            duplicates.firstOrNull { it.section == "archive" } ?: duplicates.first()
        }
    }

    /** Compatibility projection used by parser tests and diagnostics. */
    fun fromResultsPage(source: String): List<String> =
        linksFromResultsPage(source).map { it.id }

    /** Singles only: the surface-speed rating is built from singles serving stats. */
    fun singles(ids: List<String>): List<String> = ids.filter { it.startsWith("ms") }

    fun singlesLinks(links: List<MatchLink>): List<MatchLink> =
        links.filter { it.id.startsWith("ms") }
}
