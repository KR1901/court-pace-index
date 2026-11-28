package ink.courtpace.index.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.analytics.PaceAnalytics
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.theme.PaceTheme
import kotlin.math.abs

/** Two-option glass segmented control. */
@Composable
fun MetricToggle(
    metric: Metric,
    onChange: (Metric) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (colors.isDark) 0.07f else 0.55f), CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Metric.entries.forEach { option ->
            val selected = option == metric
            val fill by animateColorAsState(
                targetValue = if (selected) colors.accent.copy(alpha = if (colors.isDark) 0.28f else 0.34f)
                else Color.Transparent,
                animationSpec = tween(220),
                label = "toggle",
            )
            Text(
                text = option.short,
                style = TextStyle(
                    fontSize = 12.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                ),
                color = if (selected) colors.text else colors.textDim,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(CircleShape)
                    .background(fill, CircleShape)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onChange(option) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Direct year picker using the same YEAR chip and dropdown as Player Stats. */
@Composable
fun SeasonStepper(
    seasons: List<Int>,
    selected: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val availableSeasons = remember(seasons) { seasons.distinct().sortedDescending() }
    var yearMenu by remember { mutableStateOf(false) }

    Box(modifier) {
        GlassChip(
            text = "YEAR · " + selected,
            selected = true,
            onClick = { yearMenu = true },
            selectionRole = Role.Button,
        )
        DropdownMenu(
            expanded = yearMenu,
            onDismissRequest = { yearMenu = false },
        ) {
            availableSeasons.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.toString()) },
                    onClick = {
                        onChange(option)
                        yearMenu = false
                    },
                )
            }
        }
    }
}

/** Change against the previous edition, with the noise threshold applied. */
@Composable
fun DeltaBadge(
    delta: Double?,
    metric: Metric,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    if (delta == null) {
        Text(
            text = "new",
            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
            color = colors.textFaint,
            modifier = modifier,
        )
        return
    }
    val meaningful = PaceAnalytics.isMeaningful(delta, metric)
    val up = delta > 0
    val tint = when {
        !meaningful -> colors.textFaint
        up -> Color(0xFF8B7BFF)
        else -> Color(0xFFE07A48)
    }
    val text = when (metric) {
        Metric.CPI -> String.format(java.util.Locale.US, "%s%.1f", if (up) "+" else "−", abs(delta))
        Metric.SPEED -> String.format(java.util.Locale.US, "%s%.2f", if (up) "+" else "−", abs(delta))
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (up) "▲" else "▼",
            style = TextStyle(fontSize = 8.sp),
            color = tint,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
            color = tint,
        )
    }
}

/** Category pill for whichever metric is on screen. */
@Composable
fun ValueBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

/** Headline figure with a caption. */
@Composable
fun StatTile(
    label: String,
    value: String,
    caption: String?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    Column(modifier) {
        Text(
            text = label.uppercase(),
            style = TextStyle(
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
            ),
            color = colors.textFaint,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = value,
            style = TextStyle(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.9).sp,
            ),
            color = color,
        )
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = TextStyle(fontSize = 10.5.sp),
                color = colors.textDim,
                maxLines = 1,
            )
        }
    }
}

/** Attribution line. Every number in the app carries one of these. */
@Composable
fun SourceLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp),
        color = PaceTheme.colors.textFaint,
        modifier = modifier,
    )
}

/** Animated bar that fills to a fraction, used behind list rows. */
@Composable
fun AnimatedBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "bar",
    )
    ValueBar(fraction = animated, color = color, modifier = modifier)
}
