package ink.courtpace.index.core

import ink.courtpace.index.core.data.DatasetPolicy
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.LiveSpeedBaseline
import ink.courtpace.index.core.model.LiveSpeedPlayerProfile
import ink.courtpace.index.core.model.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LiveFeedTest {

    private val data = PaceData.parse(Datasets.raw())
    private val activeParis = LiveEvent(
        t = "paris",
        s = 2026,
        status = "in_progress",
        start = LocalDate.now().minusDays(1).toString(),
        end = LocalDate.now().plusDays(2).toString(),
        atpId = 352,
        slug = "paris",
    )

    private fun withParisLive() = data.dataset.copy(live = listOf(activeParis))

    @Test
    fun `only the Davis Cup Final 8 is live while the earlier competition remains a separate completed CALC entry`() {
        val liveFinal8 = LiveEvent(
            t = "daviscupfinal8",
            s = 2026,
            status = "in_progress",
            start = LocalDate.now().minusDays(1).toString(),
            end = LocalDate.now().plusDays(2).toString(),
            round = "Final 8",
            slug = "davis-cup-final-8-2026",
        )
        val phaseData = PaceData(data.dataset.copy(
            live = listOf(liveFinal8),
            liveSchedule = listOf(liveFinal8),
        ))

        val liveDavisIds = phaseData.liveEvents(2026, Metric.SPEED)
            .map { it.first.id }
            .filter { it == "daviscup" || it == "daviscupfinal8" }
        assertEquals(listOf("daviscupfinal8"), liveDavisIds)
        assertTrue(phaseData.isLive("daviscupfinal8", 2026))
        assertFalse(phaseData.isLive("daviscup", 2026))
        assertTrue(phaseData.liveWithoutRating(2026, Metric.SPEED).any {
            it.first.id == "daviscupfinal8"
        })
        assertTrue(phaseData.entries(2026, Metric.SPEED).any { it.first.id == "daviscup" })
        assertTrue(phaseData.entries(2026, Metric.SPEED).any { it.first.id == "daviscupfinal8" })
    }

    @Test
    fun `the bundled feed does not retain an event after its end date`() {
        assertNull("Paris ended on 8-Nov-2026 and must not remain live", data.liveEvent("paris"))
        assertFalse(data.isLive("paris"))
        assertTrue(data.dataset.live.none { it.t == "paris" && it.s == 2026 })
    }

    @Test
    fun `live is per event, not global`() {
        val liveData = PaceData(withParisLive())
        assertTrue(liveData.isLive("paris"))
        assertTrue(liveData.isLive("paris", 2026))
        assertFalse(liveData.isLive("paris", 2025))
        assertFalse(liveData.isLive("miami"))
        assertNull(liveData.liveEvent("miami"))
    }

    @Test
    fun `an active event with no rating is still listed so it can be shown`() {
        val liveData = PaceData(withParisLive())
        val pending = liveData.liveWithoutRating(2026, Metric.SPEED)
        assertTrue("Paris should appear as pending", pending.any { it.first.id == "paris" })
        // Only the speed metric: a live ace rating says nothing about the physical CPI.
        assertTrue(liveData.liveWithoutRating(2026, Metric.CPI).none { it.first.id == "paris" })
    }

    @Test
    fun `a live event that has a rating leaves the pending list`() {
        val withRating = withParisLive().copy(
            readings = data.dataset.readings + ink.courtpace.index.core.model.PaceReading(
                t = "paris", s = 2026, speed = 1.04, ace = 11.2, src = "live",
                live = true, asOf = "2026-11-04T18:00:00Z", matches = 42,
            ),
        )
        val merged = PaceData(withRating)
        assertTrue(merged.liveWithoutRating(2026, Metric.SPEED).none { it.first.id == "paris" })
        assertEquals(1.04, merged.value("paris", 2026, Metric.SPEED)!!, 1e-9)
        assertTrue(merged.seasonRows("paris").first { it.season == 2026 }.speed != null)
    }

    @Test
    fun `a fresh live value and match count take precedence over a published season value`() {
        val withoutParisSeason = data.dataset.readings.filterNot {
            it.t == "paris" && it.s == 2026 && it.speed != null
        }
        val merged = PaceData(
            withParisLive().copy(
                readings = withoutParisSeason + listOf(
                    ink.courtpace.index.core.model.PaceReading(
                        t = "paris", s = 2026, speed = 0.91, src = "tennisabstract",
                    ),
                    ink.courtpace.index.core.model.PaceReading(
                        t = "paris", s = 2026, speed = 1.12, src = "live",
                        live = true, matches = 55,
                    ),
                ),
            ),
        )

        assertEquals(1.12, merged.value("paris", 2026, Metric.SPEED)!!, 1e-9)
        assertEquals(55, merged.reading("paris", 2026)!!.matches)
        assertEquals("live", merged.reading("paris", 2026, Metric.SPEED)!!.src)
    }

    @Test
    fun `a cache without published comparisons cannot replace the richer bundled dataset`() {
        val stale = data.dataset.copy(
            readings = data.dataset.readings.map { it.copy(publishedSpeed = null) },
        )
        assertTrue(data.dataset.readings.any { it.publishedSpeed != null })
        assertEquals(data.dataset, DatasetPolicy.choose(data.dataset, stale))
    }

    @Test
    fun `a dataset without a live feed still parses`() {
        val withoutLive = data.dataset.copy(live = emptyList())
        assertTrue(PaceData(withoutLive).liveWithoutRating(2026, Metric.SPEED).isEmpty())
        assertFalse(PaceData(withoutLive).isLive("paris"))
    }

    @Test
    fun `an ATP id missing from a cached dataset is recovered`() {
        // A cache written by an earlier release has no atpId. The lookup returned null, the
        // fetch was skipped before any network call, and the feature did nothing at all.
        val bundled = withParisLive()
        val staleLive = bundled.live.map { it.copy(atpId = null, slug = null) }
        val stale = bundled.copy(live = staleLive)
        val chosen = DatasetPolicy.choose(bundled, stale)
        assertTrue("the bundled definition must win", chosen.live.all { it.atpId != null })
        // And even with nothing to recover from, the registry knows where Paris lives.
        assertEquals(352, ink.courtpace.index.core.live.AtpEvents.forTournament("paris")?.atpId)
    }

    @Test
    fun `a published dataset without a live list cannot erase the event in progress`() {
        // The workflow publishes datasets built from the asset; one produced before the live
        // feed existed has no live array, and taking it whole made the live row disappear.
        val bundled = withParisLive()
        val remote = bundled.copy(live = emptyList())
        val chosen = DatasetPolicy.choose(bundled, remote)
        assertTrue("live events must survive the swap", chosen.live.isNotEmpty())
        assertTrue(PaceData(chosen).isLive("paris"))
    }

    @Test
    fun `a completed event live estimate cannot override the final archived LOEO CALC`() {
        val ended = activeParis.copy(
            status = "completed",
            start = LocalDate.now().minusDays(7).toString(),
            end = LocalDate.now().minusDays(1).toString(),
        )
        val liveBackfill = ink.courtpace.index.core.model.PaceReading(
            t = "paris", s = 2026, speed = 1.12, src = "live",
            live = true, matches = 30,
        )
        val finalCalc = ink.courtpace.index.core.model.PaceReading(
            t = "paris", s = 2026, speed = 1.04, src = "computed",
            matches = 30,
        )
        val finished = withParisLive().copy(
            live = listOf(ended),
            liveSchedule = listOf(ended),
            readings = listOf(liveBackfill, finalCalc),
        )

        val merged = PaceData(finished)

        assertFalse("A completed event must not be marked active", merged.isLive("paris", 2026))
        assertTrue(merged.liveEvents(2026, Metric.SPEED).none { it.first.id == "paris" })
        assertNull("A completed event must not expose a cached LIVE reading", merged.liveReading("paris", 2026))
        assertEquals(1.04, merged.value("paris", 2026, Metric.SPEED)!!, 1e-9)
        assertEquals("computed", merged.reading("paris", 2026, Metric.SPEED)!!.src)
        assertEquals("computed", merged.seasonRows("paris").single().speedSource)
    }

    @Test
    fun `a completed Davis Cup Final 8 uses its separate archived CALC row`() {
        val final8Tournament = data.dataset.tournaments.firstOrNull {
            it.id == "daviscupfinal8"
        }
        assertNotNull("The Final 8 must have its own tournament identity", final8Tournament)

        val ended = LiveEvent(
            t = "daviscupfinal8",
            s = 2026,
            status = "completed",
            start = LocalDate.now().minusDays(6).toString(),
            end = LocalDate.now().minusDays(1).toString(),
            slug = "davis-cup-final-8-2026",
        )
        val liveBackfill = ink.courtpace.index.core.model.PaceReading(
            t = "daviscupfinal8", s = 2026, speed = 1.18, src = "live",
            live = true, matches = 7,
        )
        val finalCalc = ink.courtpace.index.core.model.PaceReading(
            t = "daviscupfinal8", s = 2026, speed = 1.03, src = "computed",
            matches = 7,
        )
        val finished = data.dataset.copy(
            live = data.dataset.live.filterNot { it.t == "daviscupfinal8" } + ended,
            liveSchedule = data.dataset.liveSchedule.filterNot { it.t == "daviscupfinal8" } + ended,
            readings = data.dataset.readings.filterNot {
                it.t == "daviscupfinal8" && it.s == 2026
            } + listOf(liveBackfill, finalCalc),
        )

        val merged = PaceData(finished)

        assertFalse("The completed Final 8 must leave Live now", merged.isLive("daviscupfinal8", 2026))
        assertTrue(merged.liveEvents(2026, Metric.SPEED).none {
            it.first.id == "daviscupfinal8"
        })
        assertNull("A stale live estimate must not override the archived CALC row",
            merged.liveReading("daviscupfinal8", 2026))
        assertEquals(1.03, merged.value("daviscupfinal8", 2026, Metric.SPEED)!!, 1e-9)
        assertEquals("computed", merged.speedSource("daviscupfinal8", 2026))
        assertTrue(merged.entries(2026, Metric.SPEED).any {
            it.first.id == "daviscupfinal8" && kotlin.math.abs(it.second - 1.03) < 1e-9
        })
    }

    @Test
    fun `a completed event without final CALC does not expose its stale live backfill`() {
        val ended = activeParis.copy(
            status = "completed",
            start = LocalDate.now().minusDays(7).toString(),
            end = LocalDate.now().minusDays(1).toString(),
        )
        val finished = withParisLive().copy(
            live = listOf(ended),
            liveSchedule = listOf(ended),
            readings = listOf(
                ink.courtpace.index.core.model.PaceReading(
                    t = "paris", s = 2026, speed = 1.12, src = "live",
                    live = true, matches = 30,
                ),
                ink.courtpace.index.core.model.PaceReading(
                    t = "paris", s = 2026, speed = 0.92, src = "tennisabstract",
                ),
            ),
        )

        val merged = PaceData(finished)

        assertNull(merged.liveReading("paris", 2026))
        assertEquals(0.92, merged.value("paris", 2026, Metric.SPEED)!!, 1e-9)
        assertEquals("tennisabstract", merged.speedSource("paris", 2026))
    }

    @Test
    fun `a live event stays listed once it has a rating`() {
        val withRating = withParisLive().copy(
            readings = data.dataset.readings + ink.courtpace.index.core.model.PaceReading(
                t = "paris", s = 2026, speed = 1.12, src = "live", live = true, matches = 30,
            ),
        )
        val merged = PaceData(withRating)
        // It leaves the "awaiting rating" list...
        assertTrue(merged.liveWithoutRating(2026, Metric.SPEED).none { it.first.id == "paris" })
        // ...but must still be shown as live, with its number.
        val live = merged.liveEvents(2026, Metric.SPEED)
        assertTrue(live.any { it.first.id == "paris" })
        assertEquals(1.12, merged.value("paris", 2026, Metric.SPEED)!!, 1e-9)
    }

    @Test
    fun `live events survive the dataset policy`() {
        assertTrue(DatasetPolicy.isAcceptable(data.dataset))
        val extra = data.dataset.copy(
            live = data.dataset.live + LiveEvent(t = "vienna", s = 2026, status = "in_progress"),
        )
        assertTrue(DatasetPolicy.isAcceptable(extra))
    }
    @Test
    fun `a remote dataset without the new baseline cannot erase the bundled one`() {
        val bundled = data.dataset.copy(
            speedBaseline = LiveSpeedBaseline(
                season = 2026,
                normalisation = 1.02,
                profiles = listOf(
                    LiveSpeedPlayerProfile("Player One", serveRate = 0.12, concedeRate = 0.08),
                ),
            ),
        )
        val chosen = DatasetPolicy.choose(bundled, data.dataset.copy(speedBaseline = null))
        assertEquals(bundled.speedBaseline, chosen.speedBaseline)
    }

    @Test
    fun `a live speed baseline must contain enough usable player profiles`() {
        val profiles = (1..20).map { index ->
            LiveSpeedPlayerProfile(
                name = "Player $index",
                serveRate = 0.12,
                concedeRate = 0.08,
            )
        }
        val baseline = LiveSpeedBaseline(
            season = data.dataset.meta.season,
            profileSeason = data.dataset.meta.season,
            normalisation = 1.02,
            generated = "2026-11-06T00:00:00Z",
            profiles = profiles,
        )

        assertTrue(DatasetPolicy.isAcceptable(data.dataset.copy(speedBaseline = baseline)))
        assertFalse(
            DatasetPolicy.isAcceptable(
                data.dataset.copy(speedBaseline = baseline.copy(profiles = profiles.take(19))),
            ),
        )
    }

    @Test
    fun `a live baseline rejects duplicate normalized player names`() {
        val profiles = (1..20).map { index ->
            LiveSpeedPlayerProfile(
                name = "Player $index",
                serveRate = 0.12,
                concedeRate = 0.08,
            )
        } + LiveSpeedPlayerProfile(
            name = "Pláyer 1",
            serveRate = 0.10,
            concedeRate = 0.07,
        )
        val baseline = LiveSpeedBaseline(
            season = data.dataset.meta.season,
            profileSeason = data.dataset.meta.season,
            normalisation = 1.02,
            generated = "2026-11-06T00:00:00Z",
            profiles = profiles,
        )

        assertFalse(DatasetPolicy.isAcceptable(data.dataset.copy(speedBaseline = baseline)))
    }

    @Test
    fun `an invalid opponent adjustment baseline rejects the dataset`() {
        val invalid = data.dataset.copy(
            speedBaseline = LiveSpeedBaseline(
                season = 2026,
                normalisation = -1.0,
                profiles = emptyList(),
            ),
        )
        assertFalse(DatasetPolicy.isAcceptable(invalid))
    }

    @Test
    fun `an unversioned pooled live reading is not accepted as a current-method result`() {
        val remote = data.dataset.copy(
            readings = data.dataset.readings + ink.courtpace.index.core.model.PaceReading(
                t = "paris", s = 2026, speed = 1.47, ace = 11.5, src = "live",
                live = true, matches = 47,
            ),
        )
        val chosen = DatasetPolicy.choose(data.dataset, remote)
        assertNull(PaceData(chosen).liveReading("paris", 2026))
    }

}
