package ink.courtpace.index.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.ui.components.ChartLegend
import ink.courtpace.index.ui.components.MetricToggle
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.glass.GlassTabBar
import ink.courtpace.index.ui.glass.Glyph
import ink.courtpace.index.ui.theme.CourtPaceTheme
import ink.courtpace.index.ui.theme.DefaultAccent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AccessibilityControlsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun multiSelectChipExposesCheckedStateAndComfortableTouchTarget() {
        compose.setContent {
            CourtPaceTheme(dark = true, accent = DefaultAccent) {
                GlassChip(
                    text = "Clay",
                    selected = true,
                    onClick = {},
                    modifier = Modifier.testTag("surface-chip"),
                    selectionRole = Role.Checkbox,
                )
            }
        }

        compose.onNodeWithTag("surface-chip")
            .assertIsOn()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }


    @Test
    fun metricToggleExposesItsSelectedRadioOption() {
        compose.setContent {
            CourtPaceTheme(dark = true, accent = DefaultAccent) {
                MetricToggle(
                    metric = Metric.SPEED,
                    onChange = {},
                    modifier = Modifier.testTag("metric-toggle"),
                )
            }
        }

        compose.onNodeWithText("Speed").assertIsSelected()

        val controlBounds = compose.onNodeWithTag("metric-toggle").fetchSemanticsNode().boundsInRoot
        val labelBounds = compose.onNodeWithText("Speed").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The selected metric label should be vertically centred in its control.",
            abs(controlBounds.center.y - labelBounds.center.y) <= 2f,
        )
    }

    @Test
    fun longChartLegendLabelsKeepUsableHorizontalWidth() {
        val longLabel = "Monte Carlo Masters · CALC"
        compose.setContent {
            CourtPaceTheme(dark = true, accent = DefaultAccent) {
                ChartLegend(
                    entries = listOf(
                        "Miami Masters · CALC" to Color(0xFF8B7BFF),
                        "Cincinnati Masters · CALC" to Color(0xFF4295E8),
                        longLabel to Color(0xFFE07A48),
                    ),
                    modifier = Modifier.width(220.dp),
                )
            }
        }

        compose.onNodeWithText(longLabel)
            .assertExists()
            .assertWidthIsAtLeast(160.dp)
    }


    @Test
    fun filterChipsRemainReachableInNarrowLayoutAtLargeFontScale() {
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = 1.5f,
                ),
            ) {
                CourtPaceTheme(dark = true, accent = DefaultAccent) {
                    Row(
                        modifier = Modifier.width(240.dp).horizontalScroll(rememberScrollState()),
                    ) {
                        GlassChip(text = "All", selected = true, onClick = {}, selectionRole = Role.Button)
                        GlassChip(text = "Hard", selected = false, onClick = {})
                        GlassChip(text = "Clay", selected = false, onClick = {})
                        GlassChip(
                            text = "Grass",
                            selected = false,
                            onClick = {},
                            modifier = Modifier.testTag("large-font-grass"),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("large-font-grass")
            .assertExists()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun bottomNavigationExposesSelectedTabRole() {
        compose.setContent {
            CourtPaceTheme(dark = true, accent = DefaultAccent) {
                GlassTabBar(
                    items = listOf(Glyph.GAUGE to "Pace", Glyph.LIBRARY to "Compare"),
                    selected = 0,
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("Pace").assertIsSelected()
    }

    @Test
    fun singleSelectChipExposesSelectedState() {
        compose.setContent {
            CourtPaceTheme(dark = true, accent = DefaultAccent) {
                GlassChip(
                    text = "Hard",
                    selected = true,
                    onClick = {},
                    modifier = Modifier.testTag("surface-choice"),
                    selectionRole = Role.RadioButton,
                )
            }
        }

        compose.onNodeWithTag("surface-choice").assertIsSelected()
    }
}
