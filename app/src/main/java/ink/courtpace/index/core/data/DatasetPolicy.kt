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

    fun isAcceptable(dataset: PaceDataset): Boolean {
        if (dataset.tournaments.size < MIN_TOURNAMENTS) return false
        if (dataset.readings.size < MIN_READINGS) return false
        if (dataset.meta.season < 2012) return false
        val ids = dataset.tournaments.map { it.id }.toSet()
        if (ids.size != dataset.tournaments.size) return false
        if (dataset.readings.any { it.t !in ids }) return false
        dataset.speedBaseline?.let { baseline ->
            if (baseline.season != dataset.meta.season) return false
            if (!baseline.normalisation.isFinite() || baseline.normalisation <= 0.0) return false
            if (baseline.profiles.any { profile ->
                    profile.name.isBlank() ||
                        (profile.serveRate != null && (!profile.serveRate.isFinite() || profile.serveRate !in 0.0..1.0)) ||
                        (profile.concedeRate != null && (!profile.concedeRate.isFinite() || profile.concedeRate !in 0.0..1.0))
                }) return false
        }
        return dataset.readings.all { reading ->
            (reading.cpi == null || reading.cpi in 10.0..60.0) &&
                (reading.speed == null || reading.speed in 0.30..2.0) &&
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
        val safeReadings = if (chosen.speedBaseline == null) {
            chosen.readings.filterNot { it.src == "live" && it.speed != null }
        } else {
            chosen.readings
        }
        return chosen.copy(live = enriched, readings = safeReadings, speedBaseline = speedBaseline)
    }
}
