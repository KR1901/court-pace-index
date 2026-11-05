package ink.courtpace.index.core.live

/**
 * The match ids an event actually has, taken from the links on its results page.
 *
 * Walking a guessed id range was the wrong approach: the ids are sparse in a way that does not
 * follow the draw, so ninety-five attempts found fourteen matches while the rest of the range
 * was empty. The results page links to every match's stats page, which makes its own hrefs the
 * authoritative list.
 */
object AtpMatchLinks {

    private val LINK = Regex(
        """stats-centre/(?:live|archive)/\d+/\d+/((?:ms|md)\d+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Every distinct match id linked from the page, lower-cased, in page order. */
    fun fromResultsPage(html: String): List<String> =
        LINK.findAll(html).map { it.groupValues[1].lowercase() }.distinct().toList()

    /** Singles only: the rating is built on singles serving. */
    fun singles(ids: List<String>): List<String> = ids.filter { it.startsWith("ms") }
}
