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
        /** Completed-event refs must use ATP's year-specific archive results route. */
        val completed: Boolean = false,
    )

    data class Discovery(
        val events: List<Ref>,
        val pageBytes: Int,
        /** True when the current-scores hub returned a readable page, even if no event is active. */
        val hubReadable: Boolean,
        /** Recently completed supported tour events, rated without being shown in Live now. */
        val completedEvents: List<Ref> = emptyList(),
    )

    private val legacyRefs = mapOf(
        "paris" to Ref(tournamentId = "paris", atpId = 352, slug = "paris", year = 2026),
        "atpfinals" to Ref(tournamentId = "atpfinals", atpId = 605, slug = "nitto-atp-finals", year = 2026),
    )

    // Match the route inside either an absolute URL, relative href or full rendered HTML.
    private val currentRoute = Regex(
        """(?i)scores/current/([^/?#"'\s]+)/([0-9]+)"""
    )

    // Standard tour scoring formats; special-format events (for example Next Gen) stay excluded.
    private val eligibleLevels = setOf(Level.GS, Level.FINALS, Level.M1000, Level.ATP500, Level.ATP250)

    fun forTournament(id: String): Ref? = legacyRefs[id]

    /** Build the correct ATP results route for an active or completed season event. */
    fun resultsUrl(ref: Ref): String = if (ref.completed) {
        "https://www.atptour.com/en/scores/archive/${ref.slug}/${ref.atpId}/${ref.year}/results"
    } else {
        "https://www.atptour.com/en/scores/current/${ref.slug}/${ref.atpId}/results"
    }

    /** Standard-format Slams, ATP Finals, Masters 1000, 500 and 250 events are returned. */
    fun fromCurrentScoresPage(
        source: String,
        tournaments: List<Tournament>,
        year: Int,
        liveSchedule: List<LiveEvent> = emptyList(),
        today: LocalDate = LocalDate.now(),
    ): List<Ref> {
        val eligible = tournaments.filter { it.level in eligibleLevels }
        val discovered = linkedMapOf<String, Ref>()
        currentRoute.findAll(source).forEach { match ->
            val slug = match.groupValues[1].trim()
            val atpId = match.groupValues[2].toIntOrNull() ?: return@forEach
            val tournament = resolveTournament(slug, eligible) ?: return@forEach
            if (atpId <= 0) return@forEach
            // ATP may retain a completed event's results route on the current-scores hub.
            // A route alone is not proof that the tournament is still in progress.
            val scheduled = liveSchedule.firstOrNull {
                it.t == tournament.id && it.s == year
            }
            if (scheduled != null && isExpired(scheduled, today)) return@forEach
            discovered.putIfAbsent(tournament.id, Ref(tournament.id, atpId, slug, year))
        }
        return discovered.values.toList()
    }

    /** Use bundled live-event metadata only when the current hub cannot be read or parsed. */
    fun fallback(events: List<LiveEvent>, year: Int): List<Ref> =
        events.mapNotNull { event ->
            if (isExpired(event, LocalDate.now())) return@mapNotNull null

            val known = forTournament(event.t)
            val atpId = event.atpId ?: known?.atpId ?: return@mapNotNull null
            val slug = event.slug?.takeIf { it.isNotBlank() }
                ?: known?.slug
                ?: return@mapNotNull null
            Ref(event.t, atpId, slug, event.s.takeIf { it > 0 } ?: year)
        }.distinctBy { it.tournamentId }

    /**
     * Include explicitly scheduled events once their event window has begun, even when a
     * readable ATP hub has not yet published that event's current-scores route.
     */
    fun scheduledFromSchedule(
        liveSchedule: List<LiveEvent>,
        tournaments: List<Tournament>,
        year: Int,
        today: LocalDate = LocalDate.now(),
    ): List<Ref> {
        val eligibleIds = tournaments.asSequence()
            .filter { it.level in eligibleLevels }
            .map { it.id }
            .toSet()
        return liveSchedule.mapNotNull { event ->
            if (event.s != year || event.t !in eligibleIds ||
                event.status.lowercase() != "scheduled") return@mapNotNull null
            val startDate = event.start?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val endDate = event.end?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (startDate == null || endDate == null || startDate.isAfter(today) || endDate.isBefore(today)) {
                return@mapNotNull null
            }
            val atpId = event.atpId?.takeIf { it > 0 } ?: return@mapNotNull null
            val slug = event.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Ref(event.t, atpId, slug, year)
        }.distinctBy { it.tournamentId }
    }

    /**
     * Recently finished events are rated into the selected season's table, but never returned
     * to the active Live now slate. A short lookback avoids crawling old results indefinitely.
     */
    fun recentCompletedFromSchedule(
        liveSchedule: List<LiveEvent>,
        tournaments: List<Tournament>,
        year: Int,
        today: LocalDate = LocalDate.now(),
        lookbackDays: Long = 7,
    ): List<Ref> {
        val eligibleIds = tournaments.asSequence()
            .filter { it.level in eligibleLevels }
            .map { it.id }
            .toSet()
        val cutoff = today.minusDays(lookbackDays.coerceAtLeast(0))
        return liveSchedule.mapNotNull { event ->
            if (event.s != year || event.t !in eligibleIds) return@mapNotNull null
            val endDate = event.end?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            val completed = event.status.lowercase() in setOf(
                "completed", "complete", "finished", "final",
            ) || endDate.isBefore(today)
            if (!completed || endDate.isAfter(today) || endDate.isBefore(cutoff)) {
                return@mapNotNull null
            }
            val atpId = event.atpId?.takeIf { it > 0 } ?: return@mapNotNull null
            val slug = event.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Ref(event.t, atpId, slug, year, completed = true)
        }.distinctBy { it.tournamentId }
    }

    private fun isExpired(event: LiveEvent, today: LocalDate): Boolean {
        val endDate = event.end?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val terminalStatus = event.status.lowercase() in setOf(
            "completed", "complete", "finished", "final", "cancelled",
        )
        return terminalStatus || (endDate != null && endDate.isBefore(today))
    }

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
