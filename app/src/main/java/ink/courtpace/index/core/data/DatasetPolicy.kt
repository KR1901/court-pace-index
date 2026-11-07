package ink.courtpace.index.core.data

import ink.courtpace.index.core.model.PaceDataset

/**
 * Rules for accepting a dataset that arrived over the network. A refresh must never be able
 * to make the app worse: a truncated download, an error page or a stale copy is rejected in
 * favour of what is already installed.
 */
object DatasetPolicy {

    const val MIN_TOURNAMENTS = 20
    const val MIN_READINGS = 50
    const val MIN_SPEED_BASELINE_PROFILES = 20

    fun isAcceptable(dataset: PaceDataset): Boolean {
        if (dataset.tournaments.size < MIN_TOURNAMENTS) return false
        if (dataset.readings.size < MIN_READINGS) return false
        if (dataset.meta.season < 2012) return false
        val ids = dataset.tournaments.map { it.id }.toSet()
        if (ids.size != dataset.tournaments.size) return false
        if (dataset.readings.any { it.t !in ids }) return false
        dataset.speedBaseline?.let { baseline ->
            if (baseline.season != dataset.meta.season ||
                baseline.profileSeason !in (baseline.season - 1)..baseline.season ||
                baseline.generated.isBlank() ||
                baseline.profiles.size < MIN_SPEED_BASELINE_PROFILES
            ) return false
            if (!baseline.normalisation.isFinite() || baseline.normalisation <= 0.0) return false
            // AceRating indexes profiles by normalized display name. Reject duplicate keys
            // rather than allowing archive ID collisions to overwrite one player's tendencies.
            val normalizedProfileNames = baseline.profiles.map { profile ->
                java.text.Normalizer.normalize(profile.name, java.text.Normalizer.Form.NFD)
                    .lowercase(java.util.Locale.ROOT)
                    .filter { it.isLetterOrDigit() }
            }
            if (normalizedProfileNames.distinct().size != normalizedProfileNames.size) return false
            if (baseline.profiles.any { profile ->
                    profile.name.isBlank() ||
                        (profile.serveRate != null && (!profile.serveRate.isFinite() || profile.serveRate !in 0.0..1.0)) ||
                        (profile.concedeRate != null && (!profile.concedeRate.isFinite() || profile.concedeRate !in 0.0..1.0)) ||
                        (profile.serveRateSeason != null && profile.serveRateSeason !in 2012..baseline.season) ||
                        (profile.concedeRateSeason != null && profile.concedeRateSeason !in 2012..baseline.season) ||
                        (profile.serveRateSampleMatches != null && profile.serveRateSampleMatches < 1) ||
                        (profile.serveRateSampleServicePoints != null && profile.serveRateSampleServicePoints < 1) ||
                        (profile.serveRatePriorServicePoints != null &&
                            (!profile.serveRatePriorServicePoints.isFinite() || profile.serveRatePriorServicePoints <= 0.0)) ||
                        (profile.serveRateSource != null && profile.serveRateSource !in setOf(
                            "current-season-qualified",
                            "prior-season-qualified",
                            "current-season-shrunk",
                            "prior-season-shrunk",
                        ))
                }) return false
            val usableProfiles = baseline.profiles.count { profile ->
                profile.serveRate != null || profile.concedeRate != null
            }
            if (usableProfiles < MIN_SPEED_BASELINE_PROFILES) return false
        }
        return dataset.readings.all { reading ->
            (reading.cpi == null || reading.cpi in 10.0..60.0) &&
                (reading.speed == null || reading.speed in 0.30..2.0) &&
                (reading.publishedSpeed == null || reading.publishedSpeed in 0.30..2.0) &&
                reading.s in 1990..2100
        }
    }

    /**
     * The remote copy wins when it is acceptable and carries at least as much data as the
     * bundled one, so a half-published refresh cannot shrink the app's history.
     */
    fun choose(bundled: PaceDataset, remote: PaceDataset?): PaceDataset {
        if (remote == null) return bundled
        if (!isAcceptable(remote)) return bundled
        if (remote.readings.size < bundled.readings.size) return bundled
        // An older cache can have the same row count but lack the new published TA values.
        // Do not allow it to erase richer side-by-side source data from the bundled dataset.
        val bundledComparisons = bundled.readings.filter { it.publishedSpeed != null }
            .map { it.t to it.s }.toSet()
        val remoteComparisons = remote.readings.filter { it.publishedSpeed != null }
            .map { it.t to it.s }.toSet()
        if (!remoteComparisons.containsAll(bundledComparisons)) return bundled
        // A published dataset built before the live feed existed carries no live array; taking
        // it wholesale would erase the event in progress from the screen.
        val chosen = if (remote.live.isEmpty() && bundled.live.isNotEmpty()) {
            remote.copy(live = bundled.live)
        } else {
            remote
        }
        // Entries written by an older release carry no ATP id; keep the richer definition so
        // the live fetch knows where to look.
        val enriched = chosen.live.map { event ->
            if (event.atpId != null) event
            else bundled.live.firstOrNull { it.t == event.t && it.s == event.s && it.atpId != null }
                ?: event
        }
        val speedBaseline = chosen.speedBaseline
            ?.takeIf { it.season == chosen.meta.season }
            ?: bundled.speedBaseline?.takeIf { it.season == chosen.meta.season }
        // Live readings are recomputed on-device from the current ATP match feed. Never
        // trust a static-feed live number here: older builds wrote the pooled-rate method.
        val safeReadings = chosen.readings.filterNot { it.src == "live" && it.speed != null }
        return chosen.copy(live = enriched, readings = safeReadings, speedBaseline = speedBaseline)
    }
}
