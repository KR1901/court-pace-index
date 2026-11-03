package ink.courtpace.index.core.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceEngineTest {

    @Test
    fun `flight time round trips through restitution`() {
        val dropM = 2.54
        for (cor in listOf(0.55, 0.68, 0.75, 0.82, 0.90)) {
            val flight = PaceEngine.flightTimeFor(cor, dropM)
            val recovered = PaceEngine.restitutionFromFlight(flight, dropM)
            assertEquals(cor, recovered, 1e-6)
        }
    }

    @Test
    fun `restitution from heights matches the textbook form`() {
        // ITF legal ball on concrete: 254 cm drop, 140 cm rebound.
        val e = PaceEngine.restitutionFromHeights(1.40, 2.54)
        assertEquals(0.7424, e, 1e-3)
    }

    @Test
    fun `temperature correction follows the ITF coefficient`() {
        val warm = PaceEngine.temperatureAdjustedCor(0.80, 33.0)
        assertEquals(0.80 - 0.03, warm, 1e-9)
        val cold = PaceEngine.temperatureAdjustedCor(0.80, 13.0)
        assertEquals(0.80 + 0.03, cold, 1e-9)
        val reference = PaceEngine.temperatureAdjustedCor(0.80, 23.0)
        assertEquals(0.80, reference, 1e-9)
    }

    @Test
    fun `court pace rating reproduces the published examples`() {
        // Clay-like court: high friction, high restitution.
        val clay = PaceEngine.courtPaceRating(cof = 0.80, adjustedCor = 0.88)
        assertEquals(100.0 * 0.20 + 150.0 * (0.81 - 0.88), clay, 1e-9)
        assertTrue("clay should be slow", clay < 30.0)

        // Grass-like court: low friction, low restitution.
        val grass = PaceEngine.courtPaceRating(cof = 0.56, adjustedCor = 0.74)
        assertEquals(100.0 * 0.44 + 150.0 * (0.81 - 0.74), grass, 1e-9)
        assertTrue("grass should be fast", grass > 44.0)
    }

    @Test
    fun `pace index is bounded and monotonic`() {
        assertEquals(0.0, PaceEngine.paceIndex(5.0), 1e-9)
        assertEquals(100.0, PaceEngine.paceIndex(80.0), 1e-9)
        assertTrue(PaceEngine.paceIndex(35.0) > PaceEngine.paceIndex(30.0))
        assertEquals(50.0, PaceEngine.paceIndex(35.0), 1e-6)
    }

    @Test
    fun `friction from deceleration divides by g`() {
        val deceleration = 0.6 * PaceEngine.G
        assertEquals(0.6, PaceEngine.frictionFromDeceleration(deceleration), 1e-9)
        assertEquals(0.6, PaceEngine.frictionFromDeceleration(-deceleration), 1e-9)
    }

    @Test
    fun `later bounce intervals decay geometrically`() {
        val e = 0.78
        val dropM = 2.54
        val first = PaceEngine.flightTimeFor(e, dropM)
        // Bounce n leaves at e^n * v0, so each interval is e times the previous one.
        assertEquals(e, PaceEngine.restitutionFromIntervalRatio(first, first * e), 1e-9)
        assertEquals(e, PaceEngine.restitutionFromIntervalRatio(first * e, first * e * e), 1e-9)
    }

    @Test
    fun `bounce result averages successive impacts`() {
        val dropCm = 254.0
        val dropM = dropCm / 100.0
        val e = 0.78
        val firstFlight = PaceEngine.flightTimeFor(e, dropM)
        val secondFlight = firstFlight * e
        val times = listOf(0.0, firstFlight, firstFlight + secondFlight)
        val result = PaceEngine.bounceResult(times, dropCm)
        assertTrue(result.isUsable)
        assertEquals(e, result.cor, 2e-3)
        assertEquals(3, result.impacts)
        assertTrue("spread should be small", result.spread < 0.02)
    }

    @Test
    fun `single impact is not usable`() {
        val result = PaceEngine.bounceResult(listOf(0.4), 254.0)
        assertTrue(!result.isUsable)
    }

    @Test
    fun `confidence rises with more impacts and lower spread`() {
        val weak = PaceEngine.confidence(impacts = 2, spread = 0.09, cofMeasured = false)
        val strong = PaceEngine.confidence(impacts = 4, spread = 0.01, cofMeasured = true)
        assertTrue(strong > weak)
        assertTrue(weak >= 0.10)
        assertTrue(strong <= 0.97)
    }
}
