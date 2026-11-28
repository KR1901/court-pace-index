package ink.courtpace.index.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.ui.components.MetricToggle
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.theme.CourtPaceTheme
import ink.courtpace.index.ui.theme.DefaultAccent
import org.junit.Rule
import org.junit.Test
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
        compose.onNodeWithText("CPI").assertIsNotSelected()
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
