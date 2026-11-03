package ink.courtpace.index.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.model.CoefficientBand
import ink.courtpace.index.core.model.PaceCategory
import ink.courtpace.index.ui.theme.PaceTheme
import ink.courtpace.index.ui.theme.accentFor

private const val START_ANGLE = 142f
private const val SWEEP_ANGLE = 256f

/** Circular pace gauge with the five ITF pace bands marked on the track. */
@Composable
fun IndexDial(
    index: Float,
    cpr: Double,
    category: PaceCategory,
    modifier: Modifier = Modifier,
    diameter: Dp = 236.dp,
    caption: String = "Court Pace Index",
) {
    val colors = PaceTheme.colors
    val accent = accentFor(category)
    val animated by animateFloatAsState(
        targetValue = index.coerceIn(0f, 100f),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
        label = "dial",
    )

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = size.minDimension * 0.072f
            val inset = stroke / 2f + size.minDimension * 0.035f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = Color.White.copy(alpha = if (colors.isDark) 0.07f else 0.55f),
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // Band ticks: CPR 20..50 mapped onto the sweep.
            PaceCategory.entries.forEach { band ->
                val from = ((band.lower.coerceAtLeast(20.0) - 20.0) / 30.0).toFloat()
                val to = ((band.upper.coerceAtMost(50.0) - 20.0) / 30.0).toFloat()
                drawArc(
                    color = accentFor(band).copy(alpha = if (colors.isDark) 0.34f else 0.5f),
                    startAngle = START_ANGLE + SWEEP_ANGLE * from,
                    sweepAngle = SWEEP_ANGLE * (to - from) - 3f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke * 0.34f, cap = StrokeCap.Round),
                )
            }

            val sweep = SWEEP_ANGLE * (animated / 100f)
            if (sweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            accent.copy(alpha = 0.55f),
                            accent,
                            accent.copy(alpha = 0.85f),
                        ),
                    ),
                    startAngle = START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )

                val endAngle = Math.toRadians((START_ANGLE + sweep).toDouble())
                val cx = center.x + Math.cos(endAngle).toFloat() * (arcSize.width / 2f)
                val cy = center.y + Math.sin(endAngle).toFloat() * (arcSize.height / 2f)
                drawCircle(color = accent.copy(alpha = 0.28f), radius = stroke * 0.95f, center = Offset(cx, cy))
                drawCircle(color = Color.White.copy(alpha = 0.95f), radius = stroke * 0.30f, center = Offset(cx, cy))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = index.toInt().toString(),
                style = TextStyle(
                    fontSize = 62.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-2.5).sp,
                ),
                color = colors.text,
            )
            Text(
                text = caption,
                style = TextStyle(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                ),
                color = colors.textFaint,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 13.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(accent, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "CPR ${cpr.formatOne()}",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = accent,
                )
            }
        }
    }
}

/**
 * A coefficient plotted against its ITF banding (Table 3 of ITF CS 01/02), so a number is
 * never shown without the reference it belongs to.
 */
@Composable
fun CoefficientBar(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    bandLow: Double,
    bandHigh: Double,
    caption: String,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    val accent = PaceTheme.colors.accent
    val fraction = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "coef",
    )
    val lowColor = Color(0xFF7FA8D8)
    val midColor = Color(0xFFD8B14A)
    val highColor = Color(0xFF57C08A)
    val band = when {
        value >= bandHigh -> CoefficientBand.HIGH
        value >= bandLow -> CoefficientBand.MEDIUM
        else -> CoefficientBand.LOW
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = label,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = colors.textDim,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value.formatThree(),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
                color = colors.text,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = band.label.lowercase(),
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = when (band) {
                    CoefficientBand.HIGH -> highColor
                    CoefficientBand.MEDIUM -> midColor
                    CoefficientBand.LOW -> lowColor
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(9.dp),
        ) {
            val h = size.height
            val r = h / 2f
            val splitLow = ((bandLow - min) / (max - min)).toFloat() * size.width
            val splitHigh = ((bandHigh - min) / (max - min)).toFloat() * size.width
            drawRoundRect(
                color = lowColor.copy(alpha = 0.28f),
                size = Size(splitLow, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
            )
            drawRect(
                color = midColor.copy(alpha = 0.28f),
                topLeft = Offset(splitLow, 0f),
                size = Size((splitHigh - splitLow).coerceAtLeast(1f), h),
            )
            drawRoundRect(
                color = highColor.copy(alpha = 0.28f),
                topLeft = Offset(splitHigh, 0f),
                size = Size((size.width - splitHigh).coerceAtLeast(r), h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
            )
            val knobX = size.width * animated
            drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = r * 1.5f, center = Offset(knobX, r))
            drawCircle(color = accent, radius = r * 1.35f, center = Offset(knobX, r))
            drawCircle(color = Color.White, radius = r * 0.55f, center = Offset(knobX, r))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = caption,
            style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
            color = colors.textFaint,
        )
    }
}

/** Thin confidence meter. */
@Composable
fun ConfidenceMeter(confidence: Double, modifier: Modifier = Modifier) {
    val colors = PaceTheme.colors
    val animated by animateFloatAsState(
        targetValue = confidence.toFloat().coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "conf",
    )
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Measurement confidence",
                style = TextStyle(fontSize = 13.sp),
                color = colors.textDim,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(confidence * 100).toInt()}%",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.55f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(colors.accent.copy(alpha = 0.6f), colors.accent),
                        ),
                    ),
            )
        }
    }
}

/** Compact horizontal category strip used on the home screen. */
@Composable
fun CategoryStrip(cpr: Double, modifier: Modifier = Modifier) {
    val colors = PaceTheme.colors
    val position = ((cpr - 20.0) / 30.0).coerceIn(0.0, 1.0).toFloat()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PaceCategory.entries.forEach { band ->
                val active = PaceCategory.of(cpr) == band
                Box(
                    Modifier
                        .weight(if (active) 1.35f else 1f)
                        .height(if (active) 7.dp else 5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (active) accentFor(band)
                            else accentFor(band).copy(alpha = if (colors.isDark) 0.30f else 0.45f),
                        ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Slow",
                style = TextStyle(fontSize = 10.sp),
                color = colors.textFaint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(position * 100).toInt()}% along the pace scale",
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                color = colors.textFaint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Fast",
                style = TextStyle(fontSize = 10.sp),
                color = colors.textFaint,
            )
        }
    }
}

internal fun Double.formatOne(): String = String.format(java.util.Locale.US, "%.1f", this)

internal fun Double.formatThree(): String = String.format(java.util.Locale.US, "%.3f", this)
