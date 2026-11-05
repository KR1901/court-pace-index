package ink.courtpace.index.core.live

/**
 * Where an event lives on the ATP site, held in code rather than read from the dataset.
 *
 * The first versions resolved this from the live event in the dataset, and a cached dataset
 * written by an earlier release had no `atpId` field at all - so the lookup returned null, the
 * fetch was skipped before any network call, and nothing at all happened: no rating, no error,
 * no progress. Resolving it here removes that dependency entirely.
 */
object AtpEvents {

    data class Ref(val atpId: Int, val slug: String, val year: Int)

    private val refs = mapOf(
        "paris" to Ref(atpId = 352, slug = "paris", year = 2026),
    )

    fun forTournament(id: String): Ref? = refs[id]
}
