package ink.courtpace.index.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.analytics.PaceAnalytics
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.Tournament
import ink.courtpace.index.ui.components.ChartLegend
import ink.courtpace.index.ui.components.ChartPoint
import ink.courtpace.index.ui.components.ChartSeries
import ink.courtpace.index.ui.components.DotKey
import ink.courtpace.index.ui.components.DumbbellRow
import ink.courtpace.index.ui.components.LineChart
import ink.courtpace.index.ui.components.SourceLine
import ink.courtpace.index.ui.components.SurfaceBar
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.LocalTabBarInset
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.theme.PaceTheme
import ink.courtpace.index.ui.theme.accentFor
import ink.courtpace.index.ui.theme.accentForBand
import kotlin.math.abs

private val seriesColors = listOf(
    Color(0xFF8B7BFF),
    Color(0xFF3E92D8),
    Color(0xFFE07A48),
)

@Composable
fun CompareScreen(
    data: PaceData,
    season: Int,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onOpen: (Tournament) -> Unit,
) {
    val colors = PaceTheme.colors
    val candidates = data.withHistory(Metric.CPI)
    val chosen = selected.mapNotNull { data.tournament(it) }
    val points = chosen.mapIndexed { index, tournament ->
        ChartSeries(
            label = tournament.name,
            color = seriesColors[index % seriesColors.size],
            points = data.readingsFor(tournament.id).mapNotNull { reading ->
                reading.cpi?.let { ChartPoint(reading.s.toFloat(), it.toFloat()) }
            },
        )
    }
    val values = points.flatMap { series -> series.points.map { it.y } }
    val divergences = PaceAnalytics.divergences(data, season)

    val scrollState = rememberScrollState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        Modifier
            .fillMaxWidth()
            // The inset lives inside the scroll content, not on the viewport: otherwise the
            // viewport stops at the status bar and nothing can ever scroll under it.
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(statusTop + 14.dp))
        Text(
            text = "COMPARE",
            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
            color = colors.textFaint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Courts side by side",
            style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.1).sp),
            color = colors.text,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "Pick up to three events with CPI history, then see where the physical reading and " +
                "the ace-based rating disagree.",
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.textDim,
        )

        Spacer(Modifier.height(16.dp))
        candidates.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tournament ->
                    GlassChip(
                        text = tournament.name,
                        selected = tournament.id in selected,
                        onClick = { onToggle(tournament.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (points.isNotEmpty() && values.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                SectionLabel("CPI by season")
                Spacer(Modifier.height(12.dp))
                LineChart(
                    series = points,
                    xRange = 2012f..2026f,
                    yRange = (minOf(values.min(), 18f) - 2f)..(maxOf(values.max(), 50f) + 2f),
                    yLabel = { String.format(java.util.Locale.US, "%.0f", it) },
                    xLabel = { it.toInt().toString() },
                    chartHeight = 190.dp,
                )
                Spacer(Modifier.height(12.dp))
                ChartLegend(points.map { it.label to it.color })
                Spacer(Modifier.height(10.dp))
                SourceLine("Gaps are seasons with no reading; the line is not drawn across them.")
            }
        } else {
            Spacer(Modifier.height(10.dp))
            GlassNote("Select at least one event with CPI history.")
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Where the two metrics disagree · $season")
        Spacer(Modifier.height(10.dp))
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DotKey("Court Pace Index", accentForValue(34.5, Metric.CPI))
                DotKey("Ace-rate speed", accentForValue(1.0, Metric.SPEED))
            }
            Spacer(Modifier.height(12.dp))
            if (divergences.isEmpty()) {
                Text(
                    text = "No event has both metrics in $season.",
                    style = TextStyle(fontSize = 12.5.sp),
                    color = colors.textDim,
                )
            } else {
                if (divergences.none { !it.agrees }) {
                    Text(
                        text = "In $season the two metrics broadly agree: no event is placed in a " +
                            "different part of the field by one metric and the other.",
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                        color = colors.textDim,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                divergences.forEach { divergence ->
                    DumbbellRow(
                        label = divergence.tournament.name,
                        leftPercentile = divergence.cpiPercentile.toFloat(),
                        rightPercentile = divergence.speedPercentile.toFloat(),
                        leftColor = accentForValue(divergence.cpi, Metric.CPI),
                        rightColor = accentForValue(divergence.speed, Metric.SPEED),
                    )
                }
                Spacer(Modifier.height(6.dp))
                SourceLine(
                    "Speed percentiles use calibrated CALC/LIVE ratings where available and labelled " +
                        "Tennis Abstract (TA) fallbacks otherwise. Percentiles are within the ${season} " +
                        "field for each metric. A wide gap means the bounce and ace rate tell different stories.",
                )
            }
        }

        if (chosen.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionLabel("Open a court")
            Spacer(Modifier.height(10.dp))
            chosen.forEach { tournament ->
                val cpi = data.value(tournament.id, season, Metric.CPI)
                val speed = data.value(tournament.id, season, Metric.SPEED)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { onOpen(tournament) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(accentFor(PaceAnalytics.category(cpi ?: 34.0)), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = tournament.name,
                        style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = buildString {
                            cpi?.let { append("CPI ${PaceAnalytics.format(it, Metric.CPI)}") }
                            if (cpi != null && speed != null) append("  ·  ")
                            speed?.let { append("speed ${PaceAnalytics.format(it, Metric.SPEED)}") }
                        },
                        style = TextStyle(fontSize = 11.5.sp),
                        color = colors.textDim,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        GlassNote(
            "CPI is a physical bounce measurement on a handful of show courts. Surface speed is what " +
                "the field actually did on serve across every court at the event. They answer different " +
                "questions, so a gap is information, not an error.",
        )
        Spacer(Modifier.height(LocalTabBarInset.current))
    }
}

@Composable
fun SurfacesScreen(
    data: PaceData,
    season: Int,
    metric: Metric,
) {
    val colors = PaceTheme.colors
    val averages = data.surfacesPresent(season, metric).map { surface ->
        surface to (PaceAnalytics.average(data, season, metric, surface) ?: 0.0)
    }
    val max = averages.maxOfOrNull { it.second } ?: 1.0
    val trend = PaceAnalytics.seasonTrend(data, metric)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(statusTop + 14.dp))
        Text(
            text = "SURFACES",
            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
            color = colors.textFaint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "How each surface plays",
            style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.1).sp),
            color = colors.text,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "Field averages for $season, and the direction the tour has been moving.",
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.textDim,
        )

        Spacer(Modifier.height(20.dp))
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            contentPadding = PaddingValues(20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                averages.forEach { (surface, value) ->
                    SurfaceBar(
                        label = surface.label,
                        value = value.toFloat(),
                        max = max.toFloat(),
                        color = ink.courtpace.index.ui.theme.accentForSurface(surface),
                        caption = "${PaceAnalytics.count(data, season, metric, surface)} events",
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                averages.forEach { (surface, value) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            text = PaceAnalytics.format(value, metric),
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            color = colors.text,
                        )
                        Text(
                            text = PaceAnalytics.label(value, metric),
                            style = TextStyle(fontSize = 10.sp),
                            color = colors.textFaint,
                        )
                    }
                }
            }
        }

        if (trend.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                SectionLabel("Field average by season")
                Spacer(Modifier.height(12.dp))
                val values = trend.map { it.second.toFloat() }
                LineChart(
                    series = listOf(
                        ChartSeries(
                            label = "Field average",
                            color = colors.accent,
                            points = trend.map { ChartPoint(it.first.toFloat(), it.second.toFloat()) },
                        ),
                    ),
                    xRange = trend.first().first.toFloat()..trend.last().first.toFloat(),
                    yRange = (values.min() - 2f)..(values.max() + 2f),
                    yLabel = { String.format(java.util.Locale.US, "%.0f", it) },
                    xLabel = { it.toInt().toString() },
                )
                Spacer(Modifier.height(8.dp))
                SourceLine(
                    "Mean across every event with a reading that season. Seasons with few events move " +
                        "more, so treat single-year swings with care.",
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Fastest and slowest in $season")
        Spacer(Modifier.height(10.dp))
        ink.courtpace.index.core.model.Surface.entries.forEach { surface ->
            val rows = PaceAnalytics.ranking(data, season, metric, surfaces = setOf(surface))
            if (rows.size >= 2) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(ink.courtpace.index.ui.theme.accentForSurface(surface), CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = surface.label,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${rows.size} events",
                            style = TextStyle(fontSize = 10.5.sp),
                            color = colors.textFaint,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    listOf(rows.first(), rows.last()).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (row.rank == 1) "fastest" else "slowest",
                                style = TextStyle(fontSize = 10.sp),
                                color = colors.textFaint,
                                modifier = Modifier.width(56.dp),
                            )
                            Text(
                                text = row.tournament.name,
                                style = TextStyle(fontSize = 12.5.sp),
                                color = colors.textDim,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = PaceAnalytics.format(row.value, metric),
                                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                                color = colors.text,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        GlassNote(
            "Surface labels hide a lot. Two hard courts can differ by more than a hard court and a " +
                "grass court: Madrid sits at 667 m, Paris is indoors, and ball choice changes everything.",
        )
        Spacer(Modifier.height(LocalTabBarInset.current))
    }
}

internal fun Double.formatOne(): String = String.format(java.util.Locale.US, "%.1f", this)

internal fun metricAccent(value: Double, metric: Metric): Color = when (metric) {
    Metric.CPI -> accentFor(PaceAnalytics.category(value))
    Metric.SPEED -> accentForBand(PaceAnalytics.speedBand(value))
}

internal fun gapIsLarge(gap: Double): Boolean = abs(gap) >= 0.35
