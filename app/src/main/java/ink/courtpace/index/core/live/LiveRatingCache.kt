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
    val baselineGenerated: String = "",
    /** Parsed match count on the discovery page, including matches without usable stats. */
    val discoveredMatches: Int? = null,
    /** Number of server sides retained in the opponent-adjusted calculation. */
    val adjustedServerSides: Int? = null,
    /** Number of possible server sides among matches with usable Infosys stats. */
    val totalServerSides: Int? = null,
    val aces: Int? = null,
    val adjustmentIssues: List<AceRating.AdjustmentIssue> = emptyList(),
) {
    /**
     * Old caches predate side-coverage metadata. Do not present them as if their coverage
     * were known; the caller should wait for a fresh calculation instead.
     */
    fun toAceRatingResultOrNull(): AceRating.Result? {
        val discovered = discoveredMatches ?: return null
        val adjusted = adjustedServerSides ?: return null
        val total = totalServerSides ?: return null
        val rate = aceRate ?: return null
        if (
            // A cache from an older method must be recomputed, not carried into the new
            // opponent-adjusted calculation.
            methodVersion != CURRENT_METHOD_VERSION ||
            matches <= 0 ||
            discovered < matches ||
            total != discovered * 2 ||
            adjusted < AceRating.MIN_ADJUSTED_SERVER_SIDES ||
            adjusted > total ||
            // Every parsed match contributes two candidate sides. If any are excluded,
            // their per-side diagnostics must survive the cache round-trip. This prevents
            // an older partial result (for example 50/98 from the former 0.5 cutoff) from
            // being restored as an unexplained current rating.
            adjusted + adjustmentIssues.size != matches * 2 ||
            !speed.isFinite() ||
            !rate.isFinite() ||
            rate !in 0.0..1.0
        ) return null

        return AceRating.Result(
            rating = speed,
            matches = matches,
            aces = aces ?: 0,
            aceRate = rate,
            adjustedServerSides = adjusted,
            totalServerSides = total,
            discoveredMatches = discovered,
            adjustmentIssues = adjustmentIssues,
        )
    }

    fun toJson(): String = buildString {
        append('{')
        append("\"t\":\"").append(tournamentId.replace("\"", "")).append("\",")
        append("\"s\":").append(season).append(',')
        append("\"speed\":").append(speed).append(',')
        append("\"matches\":").append(matches).append(',')
        append("\"ace\":").append(aceRate ?: "null").append(',')
        append("\"asOf\":\"").append(asOf.replace("\"", "")).append("\",")
        append("\"methodVersion\":").append(methodVersion).append(',')
        append("\"baselineGenerated\":\"").append(baselineGenerated.replace("\"", "")).append("\",")
        append("\"discoveredMatches\":").append(discoveredMatches?.toString() ?: "null").append(',')
        append("\"adjustedServerSides\":").append(adjustedServerSides?.toString() ?: "null").append(',')
        append("\"totalServerSides\":").append(totalServerSides?.toString() ?: "null").append(',')
        append("\"aces\":").append(aces?.toString() ?: "null").append(',')
        append("\"adjustmentIssues\":\"").append(encodeAdjustmentIssues(adjustmentIssues)).append("\"")
        append('}')
    }

    companion object {
        const val CURRENT_METHOD_VERSION = 6
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
                val baselineGenerated = field(raw, "baselineGenerated") ?: ""
                val discoveredMatches = field(raw, "discoveredMatches")?.toIntOrNull()
                val adjustedServerSides = field(raw, "adjustedServerSides")?.toIntOrNull()
                val totalServerSides = field(raw, "totalServerSides")?.toIntOrNull()
                val aces = field(raw, "aces")?.toIntOrNull()
                val adjustmentIssues = decodeAdjustmentIssues(field(raw, "adjustmentIssues"))
                CachedLiveRating(
                    tournamentId = tournament,
                    season = season,
                    speed = speed,
                    matches = matches,
                    aceRate = ace,
                    asOf = asOf,
                    methodVersion = methodVersion,
                    baselineGenerated = baselineGenerated,
                    discoveredMatches = discoveredMatches,
                    adjustedServerSides = adjustedServerSides,
                    totalServerSides = totalServerSides,
                    aces = aces,
                    adjustmentIssues = adjustmentIssues,
                )
            }.getOrNull()
        }

        private fun encodeAdjustmentIssues(issues: List<AceRating.AdjustmentIssue>): String {
            val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
            return encoder.encodeToString(
                issues.joinToString("\n") { issue ->
                    listOf(
                        issue.matchId.orEmpty(),
                        issue.server,
                        issue.returner,
                        issue.reason,
                    ).joinToString("|")
                }.toByteArray(Charsets.UTF_8),
            )
        }

        private fun decodeAdjustmentIssues(encoded: String?): List<AceRating.AdjustmentIssue> {
            if (encoded.isNullOrBlank()) return emptyList()
            return runCatching {
                val decoded = String(
                    java.util.Base64.getUrlDecoder().decode(encoded),
                    Charsets.UTF_8,
                )
                decoded.lineSequence().mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size != 4) return@mapNotNull null
                    AceRating.AdjustmentIssue(
                        matchId = parts[0].ifBlank { null },
                        server = parts[1],
                        returner = parts[2],
                        reason = parts[3],
                    )
                }.toList()
            }.getOrDefault(emptyList())
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
