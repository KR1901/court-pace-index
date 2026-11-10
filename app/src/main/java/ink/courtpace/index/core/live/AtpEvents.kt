package ink.courtpace.index.core.live

import ink.courtpace.index.core.model.Level
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.Tournament
import java.time.LocalDate

/** Resolves ATP score-centre routes to tournaments already known to the app. */
object AtpEvents {

    data class Ref(
        val tournamentId: String,
        val atpId: Int,
        val slug: String,
        val year: Int,
    )

    data class Discovery(
        val events: List<Ref>,
        val pageBytes: Int,
    )

    private val legacyRefs = mapOf(
        "paris" to Ref(tournamentId = "paris", atpId = 352, slug = "paris", year = 2026),
    )

    // Match the route inside either an absolute URL, relative href or full rendered HTML.
    private val currentRoute = Regex(
        """(?i)scores/current/([^/?#"'\s]+)/([0-9]+)"""
    )

    private val eligibleLevels = setOf(Level.M1000, Level.ATP500, Level.ATP250)

    fun forTournament(id: String): Ref? = legacyRefs[id]

    /** Only main-tour ATP 1000, 500 and 250 events are returned. */
    fun fromCurrentScoresPage(
        source: String,
        tournaments: List<Tournament>,
        year: Int,
    ): List<Ref> {
        val eligible = tournaments.filter { it.level in eligibleLevels }
        val discovered = linkedMapOf<String, Ref>()
        currentRoute.findAll(source).forEach { match ->
            val slug = match.groupValues[1].trim()
            val atpId = match.groupValues[2].toIntOrNull() ?: return@forEach
            val tournament = resolveTournament(slug, eligible) ?: return@forEach
            if (atpId <= 0) return@forEach
            discovered.putIfAbsent(tournament.id, Ref(tournament.id, atpId, slug, year))
        }
        return discovered.values.toList()
    }

    /** Use bundled live-event metadata only when the current hub cannot be read or parsed. */
    fun fallback(events: List<LiveEvent>, year: Int): List<Ref> =
        events.mapNotNull { event ->
            // A stale bundled live row must disappear after its published tournament end date.
            val endDate = event.end?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (endDate != null && endDate.isBefore(LocalDate.now())) return@mapNotNull null

            val known = forTournament(event.t)
            val atpId = event.atpId ?: known?.atpId ?: return@mapNotNull null
            val slug = event.slug?.takeIf { it.isNotBlank() }
                ?: known?.slug
                ?: return@mapNotNull null
            Ref(event.t, atpId, slug, event.s.takeIf { it > 0 } ?: year)
        }.distinctBy { it.tournamentId }

    private fun resolveTournament(slug: String, candidates: List<Tournament>): Tournament? {
        val parts = slug.lowercase().split('-').filter { it.isNotBlank() }
        val suffixes = parts.indices.map { parts.drop(it).joinToString("") }
        val keys = buildList {
            add(normalise(slug))
            addAll(suffixes)
            when (normalise(slug)) {
                "toronto", "montreal" -> add("canada")
            }
        }.distinct()

        return candidates.firstOrNull { tournament ->
            val tournamentKeys = listOf(
                normalise(tournament.id),
                normalise(tournament.city),
                normalise(tournament.name),
            )
            keys.any { it in tournamentKeys }
        } ?: candidates.firstOrNull { tournament ->
            val city = normalise(tournament.city)
            val id = normalise(tournament.id)
            keys.any { key ->
                key.length >= 4 && (city.startsWith(key) || id.startsWith(key))
            }
        }
    }

    private fun normalise(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
