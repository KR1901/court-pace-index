package ink.courtpace.index.core

import ink.courtpace.index.core.data.DatasetPolicy
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.data.isCurrentLiveEvent
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.PaceReading
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bundled dataset itself: a typo in a tournament id, a value off by a factor of
 * ten, or a duplicated reading would otherwise surface as a strange number on screen.
 */
class DatasetTest {

    private val data = PaceData.parse(Datasets.raw())

    @Test
    fun `dataset parses with meta and both sources`() {
        assertEquals(2026, data.dataset.meta.season)
        assertEquals(3, data.dataset.meta.sources.size)
        assertTrue(data.dataset.meta.sources.all { it.url.startsWith("https://") })
    }

    @Test
    fun `expired and terminal live events are hidden from the speed live now list`() {
        val staleStockholm = LiveEvent(
            t = "stockholm",
            s = 2026,
            status = "in_progress",
            start = "2026-11-08",
            end = "2026-11-14",
            atpId = 429,
            slug = "stockholm",
        )
        val terminalWithoutDates = LiveEvent(
            t = "paris",
            s = 2026,
            status = "completed",
        )

        assertTrue(!isCurrentLiveEvent(staleStockholm, LocalDate.of(2026, 11, 15)))
        assertTrue(!isCurrentLiveEvent(terminalWithoutDates, LocalDate.of(2026, 11, 15)))
        assertTrue(isCurrentLiveEvent(staleStockholm, LocalDate.of(2026, 11, 14)))

        val withStaleEvents = PaceData(data.dataset.copy(live = listOf(staleStockholm, terminalWithoutDates)))
        assertTrue(withStaleEvents.liveEvents(2026, Metric.SPEED).isEmpty())
        assertTrue(withStaleEvents.liveWithoutRating(2026, Metric.SPEED).isEmpty())
        assertTrue(withStaleEvents.liveById.isEmpty())
    }

    @Test
    fun `tournament ids are unique and complete`() {
        val ids = data.tournaments.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        data.tournaments.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.city.isNotBlank())
            assertEquals(3, it.country.length)
        }
    }

    @Test
    fun `every reading points at a real tournament`() {
        val orphans = data.dataset.readings.filter { data.byId[it.t] == null }
        assertTrue("orphan readings: ${orphans.map { it.t }}", orphans.isEmpty())
    }

    @Test
    fun `readings carry one primary metric or one provisional diagnostic`() {
        data.dataset.readings.forEach { reading ->
            val primary = listOfNotNull(reading.cpi, reading.speed).size
            val provisionalOnly = reading.provisionalSpeed != null &&
                reading.cpi == null && reading.speed == null
            assertTrue("reading has invalid primary/provisional metric shape: $reading",
                primary == 1 || provisionalOnly)
            assertTrue(reading.s in 1990..2030)
            assertTrue(reading.src.isNotBlank())
            reading.provisionalSpeed?.let {
                assertTrue(it in 0.30..2.0)
                assertTrue(reading.provisionalServerSides!! in 10..19)
                assertTrue(reading.provisionalCiLow!! <= reading.provisionalCiHigh!!)
                if (reading.speed == null) assertEquals("provisional", reading.src)
                else assertTrue(reading.src == "tennisabstract" || reading.src == "computed")
            }
            if (reading.speedCiLow != null && reading.speedCiHigh != null) {
                assertTrue(reading.speedCiLow <= reading.speedCiHigh)
                assertEquals("match-cluster-conditional-v1", reading.speedCiMethod)
            }
        }
    }

    @Test
    fun `values sit inside plausible ranges`() {
        data.dataset.readings.forEach { reading ->
            reading.cpi?.let { assertTrue("cpi $it at ${reading.t}", it in 10.0..60.0) }
            reading.speed?.let {
                val validStandardRating = it in 0.30..2.0
                val validSupportedStandardCalc = reading.t != "nextgen" &&
                    reading.src == "computed" &&
                    it in 2.0..DatasetPolicy.MAX_SUPPORTED_COMPUTED_SPEED &&
                    (reading.adjustedServerSides ?: 0) >= DatasetPolicy.MIN_SUPPORTED_COMPUTED_SERVER_SIDES &&
                    reading.speedCiMethod == "match-cluster-conditional-v1" &&
                    reading.speedCiLow?.isFinite() == true &&
                    reading.speedCiHigh?.isFinite() == true &&
                    reading.speedCiLow >= 0.30 &&
                    reading.speedCiHigh >= reading.speedCiLow &&
                    reading.speedCiHigh <= DatasetPolicy.MAX_SUPPORTED_COMPUTED_SPEED
                val validFormatSpecificNextGenRating =
                    reading.t == "nextgen" && reading.src in setOf("computed", "live") && it.isFinite() && it >= 0.30
                assertTrue("speed $it at ${reading.t}", validStandardRating || validSupportedStandardCalc || validFormatSpecificNextGenRating)
            }
            reading.ace?.let { assertTrue("ace $it at ${reading.t}", it in 0.0..30.0) }
        }
    }

    @Test
    fun `format-specific Next Gen live readings may exceed two while other live events may not`() {
        val readingsWithoutNextGenLive = data.dataset.readings.filterNot {
            it.t == "nextgen" && it.s == 2026 && it.src == "live"
        }
        val nextGenLive = data.dataset.copy(
            readings = readingsWithoutNextGenLive + PaceReading(
                t = "nextgen", s = 2026, speed = 2.15, src = "live",
                live = true, matches = 1,
            ),
        )
        assertTrue("Next Gen live CALC should use the format-specific range", DatasetPolicy.isAcceptable(nextGenLive))

        val standardLive = data.dataset.copy(
            readings = readingsWithoutNextGenLive + PaceReading(
                t = "atpfinals", s = 2026, speed = 2.15, src = "live",
                live = true, matches = 1,
            ),
        )
        assertTrue("standard-format live ratings must keep the normal upper bound", !DatasetPolicy.isAcceptable(standardLive))
    }

    @Test
    fun `no duplicate readings per tournament and season`() {
        val keys = data.dataset.readings.map { Triple(it.t, it.s, it.src) }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `coverage matches what the sources claim`() {
        val cpi = data.dataset.readings.count { it.cpi != null }
        val speed = data.dataset.readings.count { it.speed != null }
        assertTrue("CPI coverage dropped below its 106-reading baseline: $cpi", cpi >= 106)
        assertTrue("Speed coverage dropped below its 716-reading baseline: $speed", speed >= 716)
        assertEquals(1991, data.seasons.first())
        assertEquals(2026, data.seasons.last())
    }

    @Test
    fun `calibrated readings lead and published values remain available as comparisons`() {
        val published = data.dataset.readings.filter {
            it.speed != null && (it.src == "tennisabstract" || it.publishedSpeed != null)
        }
        val computed = data.dataset.readings.filter { it.speed != null && it.src == "computed" }
        assertTrue("published coverage unexpectedly shrank: ${published.size}", published.size >= 62)
        assertTrue("calculated coverage unexpectedly shrank: ${computed.size}", computed.size >= 685)
        val publishedYears = published.map { it.s }.toSet()
        assertEquals((1991..2026).toSet(), publishedYears)
        val calc2025Ids = computed.filter { it.s == 2025 }.map { it.t }.toSet()
        assertTrue("2025 CALC is missing restored events: $calc2025Ids",
            calc2025Ids.containsAll(setOf("marseille", "stockholm", "nextgen")))
        val nextGen2025 = computed.first { it.t == "nextgen" && it.s == 2025 }
        assertEquals("computed", nextGen2025.src)
        assertEquals(0.81, nextGen2025.publishedSpeed!!, 0.001)
        assertTrue("Next Gen must retain its complete eligible draw",
            (nextGen2025.adjustedServerSides ?: 0) >= 10)
        assertEquals("computed", data.speedSource("nextgen", 2025))
        assertEquals(nextGen2025.publishedSpeed, data.publishedSpeed("nextgen", 2025))

        val nextGen2024 = computed.first { it.t == "nextgen" && it.s == 2024 }
        assertEquals("computed", nextGen2024.src)
        assertEquals(1.39, nextGen2024.publishedSpeed!!, 0.001)
        val nextGen2024Speed = requireNotNull(nextGen2024.speed)
        assertTrue(
            "Next Gen 2024 chronological CALC above 2.0 must remain visible",
            nextGen2024Speed.isFinite() && nextGen2024Speed > 2.0,
        )
        assertEquals(15, nextGen2024.matches)
        assertEquals(30, nextGen2024.adjustedServerSides)
        val nextGen2024CiLow = requireNotNull(nextGen2024.speedCiLow)
        val nextGen2024CiHigh = requireNotNull(nextGen2024.speedCiHigh)
        assertTrue(nextGen2024CiLow.isFinite() && nextGen2024CiHigh.isFinite())
        assertTrue(nextGen2024CiLow > 0.0 && nextGen2024CiLow <= nextGen2024CiHigh)
        assertEquals("match-cluster-conditional-v1", nextGen2024.speedCiMethod)

        // The 2026 Rio and Adelaide CALC values exist in speed_history.json; make sure
        // the public dataset builder promotes those calculations and retains each live TA
        // comparison. The current-season TA reference table is refreshed, so its exact
        // published values must not be pinned in this regression test.
        val rio2026 = computed.first { it.t == "rio" && it.s == 2026 }
        assertEquals("computed", data.speedSource("rio", 2026))
        val rioPublished = requireNotNull(rio2026.publishedSpeed) {
            "2026 Rio CALC must retain its published TA comparison"
        }
        assertTrue(
            "2026 Rio published comparison is outside the valid range: $rioPublished",
            rioPublished.isFinite() && rioPublished in 0.30..2.0,
        )
        assertEquals(rioPublished, data.publishedSpeed("rio", 2026))

        val adelaide2026 = computed.first { it.t == "adelaide" && it.s == 2026 }
        assertEquals("computed", data.speedSource("adelaide", 2026))
        val adelaidePublished = requireNotNull(adelaide2026.publishedSpeed) {
            "2026 Adelaide CALC must retain its published TA comparison"
        }
        assertTrue(
            "2026 Adelaide published comparison is outside the valid range: $adelaidePublished",
            adelaidePublished.isFinite() && adelaidePublished in 0.30..2.0,
        )
        assertEquals(adelaidePublished, data.publishedSpeed("adelaide", 2026))
        assertTrue(computed.all { it.s in 1991..2026 })
        assertTrue(
            "the archive should calculate at least one event in its first supported season",
            computed.any { it.s == 1991 && it.calculationDiagnostics != null },
        )
        assertTrue(
            "every computed tournament-season must carry archived detail diagnostics",
            computed.all { it.calculationDiagnostics != null },
        )
        computed.forEach { reading ->
            val diagnostics = reading.calculationDiagnostics!!
            assertEquals(reading.matches, diagnostics.completedMatches)
            assertEquals(reading.adjustedServerSides, diagnostics.adjustedServerSides)
            assertEquals(diagnostics.completedMatches * 2, diagnostics.totalServerSides)
            assertTrue(diagnostics.sideDiagnostics.isNotEmpty())
            assertTrue(diagnostics.sideDiagnostics.size <= 20)
            assertTrue(diagnostics.sideDiagnostics.all {
                it.server.isNotBlank() && it.returner.isNotBlank() &&
                    it.expectedAces > 0.0 && it.actualExpectedRatio.isFinite()
            })
        }
        val comparisons = computed.filter { it.publishedSpeed != null }
        assertTrue("too few published/calculated comparisons: ${comparisons.size}", comparisons.size >= 31)
        val montpellier = comparisons.first {
            it.t == "montpellier" && it.s == 2026
        }
        assertEquals(montpellier.speed, data.value("montpellier", 2026, Metric.SPEED))
        assertEquals(montpellier.publishedSpeed!!, data.publishedSpeed("montpellier", 2026)!!, 0.001)
        val montpellierPublished = requireNotNull(montpellier.publishedSpeed) {
            "2026 Montpellier CALC must retain its published TA comparison"
        }
        assertTrue(
            "2026 Montpellier published comparison is outside the valid range: $montpellierPublished",
            montpellierPublished.isFinite() && montpellierPublished in 0.30..2.0,
        )
        assertEquals(montpellierPublished, data.publishedSpeed("montpellier", 2026))
        assertEquals("computed", data.speedSource("montpellier", 2026))
        // Laver Cup's 2026 seven-match draw has fourteen valid adjusted server sides.
        // It now receives a ranked CALC point estimate, with the 2026 TA value retained.
        val laverCup = data.dataset.readings.first {
            it.t == "lavercup" && it.s == 2026
        }
        assertEquals("computed", laverCup.src)
        assertEquals(7, laverCup.matches)
        assertEquals(14, laverCup.adjustedServerSides)
        val laverCupPublished = requireNotNull(laverCup.publishedSpeed) {
            "2026 Laver Cup CALC must retain its published TA comparison"
        }
        assertTrue(
            "2026 Laver Cup published comparison is outside the valid range: $laverCupPublished",
            laverCupPublished.isFinite() && laverCupPublished in 0.30..2.0,
        )
        assertEquals("computed", data.speedSource("lavercup", 2026))
        assertEquals(laverCup.speed, data.value("lavercup", 2026, Metric.SPEED))
        assertEquals(laverCup.publishedSpeed, data.publishedSpeed("lavercup", 2026))
    }

    @Test
    fun `completed tournament opens on its latest archived CALC edition`() {
        // A Next Gen 2026 board context has no 2026 edition, so opening the event
        // should land on the latest historical edition with its full diagnostics.
        assertEquals(2025, data.detailSeason("nextgen", 2026))
        assertEquals(2024, data.detailSeason("nextgen", 2024))
        assertEquals(2026, data.detailSeason("rio", 2026))

        val opened = data.seasonRows("nextgen").first {
            it.season == data.detailSeason("nextgen", 2026)
        }
        assertTrue("the default edition must include the archived CALC detail",
            opened.calculationDiagnostics != null)
    }

    @Test
    fun `supported standard CALC and format-specific Next Gen ratings may exceed two`() {
        val nextGen = data.dataset.readings.first { it.t == "nextgen" && it.s == 2025 }
        val nextGenCalc = nextGen.copy(
            speed = 2.45,
            publishedSpeed = 0.81,
            src = "computed",
        )
        val withNextGenOutlier = data.dataset.copy(
            readings = data.dataset.readings.filterNot { it.t == "nextgen" && it.s == 2025 } + nextGenCalc,
        )
        assertTrue("format-specific Next Gen CALC above 2.0 must be accepted",
            DatasetPolicy.isAcceptable(withNextGenOutlier))

        // Marseille 1995 is a standard-format event, but its 2.268 CALC is supported by
        // 31 matches, 62 adjusted sides and a finite match-cluster interval.
        val marseille = data.dataset.readings.first { it.t == "marseille" && it.s == 1995 }
        assertTrue("well-supported standard CALC above 2.0 must be accepted",
            marseille.speed!! > 2.0 && DatasetPolicy.isAcceptable(data.dataset))

        val unsupportedStandardOutlier = marseille.copy(
            speed = 2.45,
            speedCiHigh = 3.2,
        )
        val withUnsupportedOutlier = data.dataset.copy(
            readings = data.dataset.readings.filterNot { it.t == "marseille" && it.s == 1995 } +
                unsupportedStandardOutlier,
        )
        assertTrue("standard CALC without a bounded interval must be rejected",
            !DatasetPolicy.isAcceptable(withUnsupportedOutlier))

        val excessiveStandardOutlier = marseille.copy(speed = 3.01)
        val withExcessiveOutlier = data.dataset.copy(
            readings = data.dataset.readings.filterNot { it.t == "marseille" && it.s == 1995 } +
                excessiveStandardOutlier,
        )
        assertTrue("standard CALC above the supported ceiling must be rejected",
            !DatasetPolicy.isAcceptable(withExcessiveOutlier))
    }

    @Test
    fun `Laver Cup host city is resolved per edition without rewriting history`() {
        val edition2025 = data.tournament("lavercup", 2025)!!
        val edition2026 = data.tournament("lavercup", 2026)!!

        assertEquals("San Francisco", edition2025.city)
        assertEquals("USA", edition2025.country)
        assertEquals("London", edition2026.city)
        assertEquals("GBR", edition2026.country)
        assertEquals("London", data.entries(2026, Metric.SPEED)
            .first { it.first.id == "lavercup" }.first.city)
    }

    @Test
    fun `computed history covers most of the tour in a middle season`() {
        val events2019 = data.tournaments.count { data.value(it.id, 2019, Metric.SPEED) != null }
        assertTrue("only $events2019 events rated in 2019", events2019 >= 30)
        val events2013 = data.tournaments.count { data.value(it.id, 2013, Metric.SPEED) != null }
        assertTrue("only $events2013 events rated in 2013", events2013 >= 20)
    }

    @Test
    fun `2026 has enough overlap to compare the two metrics`() {
        val both = data.tournaments.count {
            data.value(it.id, 2026, Metric.CPI) != null && data.value(it.id, 2026, Metric.SPEED) != null
        }
        assertTrue("only $both events have both metrics", both >= 8)
    }

    @Test
    fun `every rated tournament has at least one reading`() {
        data.tournaments.forEach { tournament ->
            val readings = data.readingsFor(tournament.id)
            // Davis Cup is deliberately introduced as a schedule-only event before the
            // historical archive refresh publishes its 2016–2024 CALC readings. The
            // production release workflow separately requires those historical readings,
            // so this exception cannot allow an incomplete release to be published.
            val scheduledDavisCup =
                tournament.id == "daviscup" &&
                    data.dataset.live.any { it.t == "daviscup" && it.s == 2026 } &&
                    data.dataset.liveSchedule.any { it.t == "daviscup" && it.s == 2026 }
            assertTrue(
                "${tournament.id} has no readings and is not an explicitly schedule-only event",
                readings.isNotEmpty() || scheduledDavisCup,
            )
        }
    }
}
