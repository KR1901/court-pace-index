package ink.courtpace.index.core.playerstats

enum class PlayerRankingMetric(val label: String) {
    HOLD("Hold %"),
    BREAK("Break %"),
    COMBINED("Hold + Break"),
}

enum class PlayerSurfaceFilter(val id: String, val label: String) {
    ALL("all", "All surfaces"),
    CLAY("clay", "Clay"),
    GRASS("grass", "Grass"),
    OUTDOOR_HARD("hardOutdoor", "Outdoor hard"),
    INDOOR_HARD("hardIndoor", "Indoor hard"),
    ALL_HARD("hardAll", "All hard"),
    CARPET("carpet", "Carpet"),
}

private data class HeightBand(
    val id: String,
    val label: String,
    val minCm: Int?,
    val maxCm: Int?,
)

data class PlayerRankingRow(
    val rank: Int?,
    val player: PlayerStatsPlayer,
    val holdPct: Double?,
    val breakPct: Double?,
    val combined: Double?,
    val score: Double?,
    val serviceGames: Int,
    val returnGames: Int,
    val matches: Int,
    val wins: Int,
    val losses: Int,
    val qualified: Boolean,
)

data class HeightGroupSummary(
    val id: String,
    val label: String,
    val minCm: Int?,
    val maxCm: Int?,
    val players: Int,
    val playersWithSeasonData: Int,
    val serviceGames: Int,
    val serviceGamesWon: Int,
    val returnGames: Int,
    val returnGamesWon: Int,
    val matches: Int,
    val wins: Int,
    val losses: Int,
) {
    val holdPct: Double?
        get() = PlayerStatsAnalytics.percentage(serviceGamesWon, serviceGames)
    val breakPct: Double?
        get() = PlayerStatsAnalytics.percentage(returnGamesWon, returnGames)
    val combined: Double?
        get() = if (holdPct != null && breakPct != null) holdPct + breakPct else null
    val winPct: Double?
        get() = PlayerStatsAnalytics.percentage(wins, wins + losses)
}

data class HeightGroupsResult(
    val groups: List<HeightGroupSummary>,
    val cohortSize: Int,
    val heightKnown: Int,
    val unassignedHeight: Int,
)

object PlayerStatsAnalytics {
    private val heightBands = listOf(
        HeightBand("under183", "Under 1.83 m / 6′0″", null, 182),
        HeightBand("183to190", "1.83–1.90 m / 6′0″–6′2.8″", 183, 190),
        HeightBand("191to194", "1.91–1.94 m / 6′3.2″–6′4.4″", 191, 194),
        HeightBand("195plus", "1.95 m+ / 6′4.8″+", 195, null),
    )

    fun percentage(numerator: Int, denominator: Int): Double? =
        if (denominator > 0 && numerator >= 0) numerator.toDouble() * 100.0 / denominator else null

    fun heightBandId(heightCm: Int?): String? = when {
        heightCm == null || heightCm !in 140..220 -> null
        heightCm < 183 -> "under183"
        heightCm <= 190 -> "183to190"
        heightCm <= 194 -> "191to194"
        else -> "195plus"
    }

    fun heightText(heightCm: Int?): String {
        if (heightCm == null || heightCm !in 140..220) return "Height unavailable"
        val totalInches = heightCm / 2.54
        val feet = (totalInches / 12.0).toInt()
        val inches = totalInches - feet * 12.0
        val metres = (heightCm / 100).toString() + "." + (heightCm % 100).toString().padStart(2, '0') + " m"
        return metres + " · " + feet + "′" + String.format(Locale.US, "%.1f", inches) + "″"
    }

    fun rankings(
        data: PlayerStatsData,
        year: Int,
        surface: PlayerSurfaceFilter,
        metric: PlayerRankingMetric,
        minimumGames: Int,
    ): List<PlayerRankingRow> {
        val all = data.players.map { player ->
            val bucket = player.seasons.firstOrNull { it.y == year }?.b?.get(surface.id)
            val hold = bucket?.let { percentage(it.sw, it.sp) }
            val breakRate = bucket?.let { percentage(it.rw, it.rp) }
            val combined = if (hold != null && breakRate != null) hold + breakRate else null
            val games = when (metric) {
                PlayerRankingMetric.HOLD -> bucket?.sp ?: 0
                PlayerRankingMetric.BREAK -> bucket?.rp ?: 0
                PlayerRankingMetric.COMBINED -> minOf(bucket?.sp ?: 0, bucket?.rp ?: 0)
            }
            val score = when (metric) {
                PlayerRankingMetric.HOLD -> hold
                PlayerRankingMetric.BREAK -> breakRate
                PlayerRankingMetric.COMBINED -> combined
            }
            PlayerRankingRow(
                rank = null,
                player = player,
                holdPct = hold,
                breakPct = breakRate,
                combined = combined,
                score = score,
                serviceGames = bucket?.sp ?: 0,
                returnGames = bucket?.rp ?: 0,
                matches = bucket?.mp ?: 0,
                wins = bucket?.w ?: 0,
                losses = bucket?.l ?: 0,
                qualified = score != null && games >= minimumGames,
            )
        }
        val sorted = all.sortedWith(
            compareByDescending<PlayerRankingRow> { it.qualified }
                .thenByDescending { it.score ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.player.name.lowercase() },
        )
        var rank = 0
        return sorted.map { row ->
            if (row.qualified) {
                rank += 1
                row.copy(rank = rank)
            } else {
                row
            }
        }
    }

    fun heightGroups(
        data: PlayerStatsData,
        year: Int,
        surface: PlayerSurfaceFilter,
    ): HeightGroupsResult {
        val cohort = data.players.filter { it.currentRank != null }
        val buckets = heightBands.associate { it.id to mutableListOf<PlayerStatsPlayer>() }
        var unknown = 0
        cohort.forEach { player ->
            val band = heightBandId(player.heightCm)
            if (band == null) unknown += 1 else buckets[band]?.add(player)
        }
        val groups = heightBands.map { band ->
            val players = buckets[band.id].orEmpty()
            var sp = 0
            var sw = 0
            var rp = 0
            var rw = 0
            var mp = 0
            var wins = 0
            var losses = 0
            var withData = 0
            players.forEach { player ->
                val bucket = player.seasons.firstOrNull { it.y == year }?.b?.get(surface.id)
                if (bucket != null && bucket.mp > 0) withData += 1
                if (bucket != null) {
                    sp += bucket.sp
                    sw += bucket.sw
                    rp += bucket.rp
                    rw += bucket.rw
                    mp += bucket.mp
                    wins += bucket.w
                    losses += bucket.l
                }
            }
            HeightGroupSummary(
                id = band.id,
                label = band.label,
                minCm = band.minCm,
                maxCm = band.maxCm,
                players = players.size,
                playersWithSeasonData = withData,
                serviceGames = sp,
                serviceGamesWon = sw,
                returnGames = rp,
                returnGamesWon = rw,
                matches = mp,
                wins = wins,
                losses = losses,
            )
        }
        return HeightGroupsResult(
            groups = groups,
            cohortSize = cohort.size,
            heightKnown = cohort.size - unknown,
            unassignedHeight = unknown,
        )
    }
}
