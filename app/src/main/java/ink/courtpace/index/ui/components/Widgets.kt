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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.analytics.PaceAnalytics
import ink.courtpace.index.core.model.Metric
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
                    .clip(CircleShape)
                    .background(fill, CircleShape)
                    .clickable { onChange(option) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Season stepper: ‹ 2026 ›, moving only through seasons that hold data. */
@Composable
fun SeasonStepper(
    seasons: List<Int>,
    selected: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    val index = seasons.indexOf(selected)
        .let { if (it >= 0) it else seasons.indexOf(nearest(seasons, selected)) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepperArrow("‹", enabled = index > 0) {
            if (index > 0) onChange(seasons[index - 1])
        }
        Text(
            text = selected.toString(),
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
            ),
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        StepperArrow("›", enabled = index < seasons.lastIndex) {
            if (index < seasons.lastIndex) onChange(seasons[index + 1])
        }
    }
}

private fun nearest(seasons: List<Int>, value: Int): Int =
    seasons.minByOrNull { kotlin.math.abs(it - value) } ?: seasons.first()

@Composable
private fun StepperArrow(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = PaceTheme.colors
    // The visual circle is 34dp but the touch area must be at least 48dp.
    Box(
        Modifier
            .size(48.dp)
            .clickable(
                enabled = enabled,
                onClickLabel = if (symbol == "\u2039") "Previous season" else "Next season",
                role = Role.Button,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.6f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = symbol,
                style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
                color = if (enabled) colors.text else colors.textFaint.copy(alpha = 0.4f),
            )
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
