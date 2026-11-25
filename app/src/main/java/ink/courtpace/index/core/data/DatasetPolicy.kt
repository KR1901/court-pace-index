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
                        (profile.serveRateSeason != null && profile.serveRateSeason !in 1991..baseline.season) ||
                        (profile.concedeRateSeason != null && profile.concedeRateSeason !in 1991..baseline.season) ||
                        (profile.serveRateSampleMatches != null &&
                            (profile.serveRateSampleMatches < 0 ||
                                (profile.serveRateSampleMatches == 0 &&
                                    profile.serveRate != null &&
                                    profile.serveRateSource != "tour-average-prior-only"))) ||
                        (profile.serveRateSampleServicePoints != null &&
                            (profile.serveRateSampleServicePoints < 0 ||
                                (profile.serveRateSampleServicePoints == 0 &&
                                    profile.serveRate != null &&
                                    profile.serveRateSource != "tour-average-prior-only"))) ||
                        (profile.concedeRateSampleMatches != null &&
                            (profile.concedeRateSampleMatches < 0 ||
                                (profile.concedeRateSampleMatches == 0 &&
                                    profile.concedeRate != null &&
                                    profile.concedeRateSource != "tour-average-prior-only"))) ||
                        (profile.concedeRateSampleServicePoints != null &&
                            (profile.concedeRateSampleServicePoints < 0 ||
                                (profile.concedeRateSampleServicePoints == 0 &&
                                    profile.concedeRate != null &&
                                    profile.concedeRateSource != "tour-average-prior-only"))) ||
                        (profile.serveRatePriorServicePoints != null &&
                            (!profile.serveRatePriorServicePoints.isFinite() || profile.serveRatePriorServicePoints <= 0.0)) ||
                        (profile.concedeRatePriorServicePoints != null &&
                            (!profile.concedeRatePriorServicePoints.isFinite() || profile.concedeRatePriorServicePoints <= 0.0)) ||
                        (profile.serveRateSource != null && profile.serveRateSource !in setOf(
                            "current-season-qualified",
                            "prior-season-qualified",
                            "current-season-shrunk",
                            "prior-season-shrunk",
                            "career-history-surface-shrunk",
                            "career-history-pooled-shrunk",
                            "tour-average-prior-only",
                        )) ||
                        (profile.concedeRateSource != null && profile.concedeRateSource !in setOf(
                            "surface-qualified",
                            "pooled-fallback",
                            "career-history-surface-shrunk",
                            "career-history-pooled-shrunk",
                            "tour-average-prior-only",
                        ))
                }) return false
            val usableProfiles = baseline.profiles.count { profile ->
                profile.serveRate != null || profile.concedeRate != null
            }
            if (usableProfiles < MIN_SPEED_BASELINE_PROFILES) return false
        }
        return dataset.readings.all { reading ->
            val speed = reading.speed
            val speedIsValid = speed == null || (
                speed.isFinite() && speed >= 0.30 &&
                    (speed <= 2.0 || (reading.t == "nextgen" && reading.src in setOf("computed", "live")))
            )
            (reading.cpi == null || reading.cpi in 10.0..60.0) &&
                speedIsValid &&
                (reading.publishedSpeed == null ||
                    (reading.publishedSpeed.isFinite() && reading.publishedSpeed in 0.30..2.0)) &&
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
        // Preserve the pre-existing fallback: if a remote snapshot has no live list,
        // retain the bundled slate. If it has an ATP slate, keep that slate authoritative
        // and carry forward only the separately sourced Davis Cup row when absent.
        val remoteLiveKeys = remote.live.map { it.t to it.s }.toSet()
        val bundledLiveFallback = if (remote.live.isEmpty()) {
            bundled.live
        } else {
            bundled.live.filter {
                it.t == "daviscup" && (it.t to it.s) !in remoteLiveKeys
            }
        }
        val combinedLive = remote.live + bundledLiveFallback
        // Entries written by an older release carry no ATP id; keep the richer definition so
        // the live fetch knows where to look.
        val enriched = combinedLive.map { event ->
            if (event.atpId != null) event
            else bundled.live.firstOrNull { it.t == event.t && it.s == event.s && it.atpId != null }
                ?: event
        }
        val chosen = remote.copy(live = enriched)
        val speedBaseline = chosen.speedBaseline
            ?.takeIf { it.season == chosen.meta.season }
            ?: bundled.speedBaseline?.takeIf { it.season == chosen.meta.season }
        // Live readings are recomputed on-device from the current ATP match feed. Never
        // trust a static-feed live number here: older builds wrote the pooled-rate method.
        val safeReadings = chosen.readings.filterNot { it.src == "live" && it.speed != null }
        return chosen.copy(readings = safeReadings, speedBaseline = speedBaseline)
    }
}
