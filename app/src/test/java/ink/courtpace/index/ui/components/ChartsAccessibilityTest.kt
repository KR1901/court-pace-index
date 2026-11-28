package ink.courtpace.index.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartsAccessibilityTest {

    @Test
    fun spokenSummarySkipsNonFinitePointsAndReportsEndpointsAndExtrema() {
        val description = lineChartDescription(
            series = listOf(
                ChartSeries(
                    label = "CPI",
                    color = Color.Red,
                    points = listOf(
                        ChartPoint(2020f, 30f),
                        ChartPoint(Float.NaN, 99f),
                        ChartPoint(2021f, Float.POSITIVE_INFINITY),
                        ChartPoint(2022f, 45f),
                        ChartPoint(2023f, 35f),
                    ),
                ),
            ),
            xLabel = { it.toInt().toString() },
            yLabel = { it.toInt().toString() },
            referenceLabel = "Reference 40",
        )

        assertTrue(description.contains("3 readings"))
        assertTrue(description.contains("from 2020 at 30"))
        assertTrue(description.contains("to 2023 at 35"))
        assertTrue(description.contains("low 30 in 2020"))
        assertTrue(description.contains("high 45 in 2022"))
        assertTrue(description.contains("Reference: Reference 40"))
        assertFalse(description.contains("99"))
    }

    @Test
    fun spokenSummaryHandlesSeriesWithoutValidPoints() {
        val description = lineChartDescription(
            series = listOf(
                ChartSeries(
                    label = "Speed",
                    color = Color.Blue,
                    points = listOf(
                        ChartPoint(Float.NaN, 1f),
                        ChartPoint(2024f, Float.NEGATIVE_INFINITY),
                    ),
                ),
            ),
            xLabel = { it.toInt().toString() },
            yLabel = { it.toString() },
            referenceLabel = null,
        )

        assertTrue(description.contains("Speed: no readings"))
    }

    @Test
    fun spokenSummaryHandlesNoSeries() {
        val description = lineChartDescription(
            series = emptyList(),
            xLabel = { it.toString() },
            yLabel = { it.toString() },
            referenceLabel = null,
        )

        assertEquals("Line chart with no readings", description)
    }
}
