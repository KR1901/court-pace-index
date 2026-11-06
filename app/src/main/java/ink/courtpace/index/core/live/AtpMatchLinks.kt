package ink.courtpace.index.core.live

/**
 * A match-statistics link found on an ATP results page.
 *
 * Keep the section/year/event id from the href as metadata. The match id is then used with the
 * tournament event id to request the corresponding Infosys Stats Plus JSON record.
 */
object AtpMatchLinks {

    data class MatchLink(
        val id: String,
        val section: String,
        val year: Int,
        val atpId: Int,
    )

    // ATP's match detail pages use /match-stats/{archive|live}/... . The older
    // stats-centre route is also accepted as a discovery alias, but is not the page URL
    // used for the per-match statistics cards.
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
