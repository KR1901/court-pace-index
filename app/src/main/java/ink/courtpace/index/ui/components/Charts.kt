package ink.courtpace.index.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.ui.theme.PaceTheme

data class ChartPoint(val x: Float, val y: Float)

data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<ChartPoint>,
)

/**
 * Line chart with a fixed y-axis on the left and season labels underneath. Missing seasons
 * are drawn as gaps rather than joined up, because a straight line across a COVID year
 * would be an invented reading.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    xRange: ClosedFloatingPointRange<Float>,
    yRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 170.dp,
    yTicks: Int = 3,
    yLabel: (Float) -> String,
    xLabel: (Float) -> String,
    referenceY: Float? = null,
    referenceLabel: String? = null,
) {
    val colors = PaceTheme.colors
    val axisWidth = 38.dp
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .width(axisWidth)
                    .height(chartHeight),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                (0 until yTicks).forEach { index ->
                    val fraction = index / (yTicks - 1).toFloat()
                    val value = yRange.endInclusive - (yRange.endInclusive - yRange.start) * fraction
                    Text(
                        text = yLabel(value),
                        style = TextStyle(fontSize = 9.5.sp),
                        color = colors.textFaint,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Canvas(
                Modifier
                    .weight(1f)
                    .height(chartHeight),
            ) {
                val w = size.width
                val h = size.height
                fun px(x: Float): Float =
                    ((x - xRange.start) / (xRange.endInclusive - xRange.start)) * w

                fun py(y: Float): Float =
                    h - ((y - yRange.start) / (yRange.endInclusive - yRange.start)) * h

                // grid
                (0 until yTicks).forEach { index ->
                    val fraction = index / (yTicks - 1).toFloat()
                    val y = h * fraction
                    drawLine(
                        color = Color.White.copy(alpha = if (colors.isDark) 0.07f else 0.5f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                    )
                }

                referenceY?.let { reference ->
                    if (reference in yRange.start..yRange.endInclusive) {
                        drawLine(
                            color = colors.textFaint.copy(alpha = 0.5f),
                            start = Offset(0f, py(reference)),
                            end = Offset(w, py(reference)),
                            strokeWidth = 1.2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }
                }

                series.forEach { line ->
                    val sorted = line.points.sortedBy { it.x }
                    sorted.zipWithNext { a, b ->
                        // Only join consecutive seasons; a gap stays a gap.
                        if (b.x - a.x <= 1.01f) {
                            drawLine(
                                color = line.color,
                                start = Offset(px(a.x), py(a.y)),
                                end = Offset(px(b.x), py(b.y)),
                                strokeWidth = 3f,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    sorted.forEach { point ->
                        drawCircle(
                            color = line.color.copy(alpha = 0.30f),
                            radius = 7f,
                            center = Offset(px(point.x), py(point.y)),
                        )
                        drawCircle(
                            color = line.color,
                            radius = 3.6f,
                            center = Offset(px(point.x), py(point.y)),
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = axisWidth + 8.dp, top = 6.dp)) {
            listOf(xRange.start, (xRange.start + xRange.endInclusive) / 2f, xRange.endInclusive).forEachIndexed { i, x ->
                Text(
                    text = xLabel(x),
                    style = TextStyle(fontSize = 9.5.sp),
                    color = colors.textFaint,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (referenceY != null && referenceLabel != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = referenceLabel,
                style = TextStyle(fontSize = 10.sp),
                color = colors.textFaint,
            )
        }
    }
}

/**
 * Two dots on one track: where the physical CPI places a court, and where the ace-based
 * rating places it. The distance between them is the disagreement.
 */
@Composable
fun DumbbellRow(
    label: String,
    leftPercentile: Float,
    rightPercentile: Float,
    leftColor: Color,
    rightColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            text = label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = colors.text,
        )
        Spacer(Modifier.height(7.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(16.dp),
        ) {
            val y = size.height / 2f
            val left = leftPercentile.coerceIn(0f, 1f) * size.width
            val right = rightPercentile.coerceIn(0f, 1f) * size.width
            drawLine(
                color = Color.White.copy(alpha = if (colors.isDark) 0.12f else 0.55f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = colors.textFaint.copy(alpha = 0.5f),
                start = Offset(minOf(left, right), y),
                end = Offset(maxOf(left, right), y),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
            drawCircle(leftColor, radius = 7f, center = Offset(left, y))
            drawCircle(rightColor, radius = 7f, center = Offset(right, y))
        }
    }
}

/** Horizontal magnitude bar used inside list rows. */
@Composable
fun ValueBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
) {
    val colors = PaceTheme.colors
    Canvas(modifier.fillMaxWidth().height(height)) {
        val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(
            color = Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.55f),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = color,
            size = androidx.compose.ui.geometry.Size(
                (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(size.height),
                size.height,
            ),
            cornerRadius = radius,
        )
    }
}

/** Vertical bar for comparing surface averages. */
@Composable
fun SurfaceBar(
    label: String,
    value: Float,
    max: Float,
    color: Color,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val colors = PaceTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .width(46.dp)
                .height(120.dp),
        ) {
            val fraction = (value / max).coerceIn(0f, 1f)
            val barHeight = size.height * fraction
            drawRoundRect(
                color = Color.White.copy(alpha = if (colors.isDark) 0.08f else 0.5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(size.width, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = colors.textDim,
        )
        if (caption != null) {
            Text(
                text = caption,
                style = TextStyle(fontSize = 10.sp),
                color = colors.textFaint,
            )
        }
    }
}

/** Faint stroke used to separate sections without a hard rule. */
@Composable
fun ChartLegend(entries: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    val colors = PaceTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(14.dp).height(4.dp)) {
                    drawLine(
                        color = color,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 3.5f,
                        cap = StrokeCap.Round,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = TextStyle(fontSize = 10.5.sp),
                    color = colors.textDim,
                )
            }
        }
    }
}

/** Small dot-and-label key for the two metrics. */
@Composable
fun DotKey(label: String, color: Color, modifier: Modifier = Modifier) {
    val colors = PaceTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(8.dp).height(8.dp)) {
            drawCircle(color = color, radius = size.minDimension / 2)
        }
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = TextStyle(fontSize = 10.5.sp), color = colors.textDim)
    }
}
