package ink.courtpace.index.core.live

/**
 * The last computed live rating, kept so the number is on screen the instant the app opens.
 *
 * Without it every open started the whole page walk from nothing, which is why the row sat on
 * "page 1" while the work happened and why nothing appeared "instantly".
 */
data class CachedLiveRating(
    val tournamentId: String,
    val season: Int,
    val speed: Double,
    val matches: Int,
    val aceRate: Double?,
    val asOf: String,
    val methodVersion: Int = CURRENT_METHOD_VERSION,
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"t\":\"").append(tournamentId.replace("\"", "")).append("\",")
        append("\"s\":").append(season).append(',')
        append("\"speed\":").append(speed).append(',')
        append("\"matches\":").append(matches).append(',')
        append("\"ace\":").append(aceRate ?: "null").append(',')
        append("\"asOf\":\"").append(asOf.replace("\"", "")).append("\",")
        append("\"methodVersion\":").append(methodVersion)
        append('}')
    }

    companion object {
        const val CURRENT_METHOD_VERSION = 2
        const val LEGACY_METHOD_VERSION = 1
        /** Tolerant on purpose: a corrupt or older cache must never break the app. */
        fun fromJson(raw: String?): CachedLiveRating? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val tournament = field(raw, "t") ?: return null
                val season = field(raw, "s")?.toIntOrNull() ?: return null
                val speed = field(raw, "speed")?.toDoubleOrNull() ?: return null
                val matches = field(raw, "matches")?.toIntOrNull() ?: 0
                val ace = field(raw, "ace")?.toDoubleOrNull()
                val asOf = field(raw, "asOf") ?: ""
                val methodVersion = field(raw, "methodVersion")?.toIntOrNull() ?: LEGACY_METHOD_VERSION
                CachedLiveRating(tournament, season, speed, matches, ace, asOf, methodVersion)
            }.getOrNull()
        }

        private fun field(raw: String, name: String): String? {
            val key = "\"$name\":"
            val start = raw.indexOf(key)
            if (start < 0) return null
            val from = start + key.length
            val end = when (val first = raw.getOrNull(from)) {
                '"' -> raw.indexOf('"', from + 1).takeIf { it > 0 } ?: return null
                else -> raw.indexOfAny(charArrayOf(',', '}'), from).takeIf { it > 0 } ?: return null
            }
            val value = raw.substring(from, end).trim().trim('"')
            return value.ifEmpty { null }
        }
    }
}
