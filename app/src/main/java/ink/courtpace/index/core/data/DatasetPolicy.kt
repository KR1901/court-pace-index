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
        return remote
    }
}
