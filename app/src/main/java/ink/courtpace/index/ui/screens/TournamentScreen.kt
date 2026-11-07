package ink.courtpace.index.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.analytics.PaceAnalytics
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.Tournament
import ink.courtpace.index.ui.components.ChartPoint
import ink.courtpace.index.ui.components.ChartSeries
import ink.courtpace.index.ui.components.DeltaBadge
import ink.courtpace.index.ui.components.LineChart
import ink.courtpace.index.ui.components.SourceLine
import ink.courtpace.index.ui.components.StatTile
import ink.courtpace.index.ui.components.ValueBadge
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.LocalTabBarInset
import ink.courtpace.index.ui.glass.HairLine
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.glass.StatRow
import ink.courtpace.index.ui.theme.PaceTheme
import ink.courtpace.index.ui.theme.accentForSurface

@Composable
fun TournamentScreen(
    data: PaceData,
    tournament: Tournament,
    season: Int,
    onBack: () -> Unit,
) {
    val colors = PaceTheme.colors
    val cpi = data.value(tournament.id, season, Metric.CPI)
    val speed = data.value(tournament.id, season, Metric.SPEED)
    val speedSource = data.speedSource(tournament.id, season)
    val publishedSpeed = data.publishedSpeed(tournament.id, season)
    val latestCpiSeason = data.latestSeason(tournament.id, Metric.CPI)
    val shownCpi = cpi ?: latestCpiSeason?.let { data.value(tournament.id, it, Metric.CPI) }
    val shownCpiSeason = if (cpi != null) season else latestCpiSeason
    val accent = accentForSurface(tournament.surface)

    val cpiPoints = data.readingsFor(tournament.id)
        .mapNotNull { reading -> reading.cpi?.let { ChartPoint(reading.s.toFloat(), it.toFloat()) } }
    val cpiValues = cpiPoints.map { it.y }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 48dp touch area; the visible circle stays 40dp.
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(onClickLabel = "Back", role = Role.Button) { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "\u2039",
                        style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
                        color = colors.text,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = "${tournament.level.label.uppercase()} · ${tournament.surfaceLabel.uppercase()}",
                    style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
                    color = colors.textFaint,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tournament.name,
                    style = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
                    color = colors.text,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${tournament.location} · ${tournament.setting}",
                        style = TextStyle(fontSize = 12.sp),
                        color = colors.textDim,
                        modifier = Modifier.weight(1f),
                    )
                    if (tournament.altitudeM != null) {
                        ValueBadge(text = "${tournament.altitudeM} m", color = colors.textFaint)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (shownCpi != null) {
                        StatTile(
                            label = "CPI ${shownCpiSeason ?: ""}",
                            value = PaceAnalytics.format(shownCpi, Metric.CPI),
                            caption = PaceAnalytics.category(shownCpi).label,
                            color = accentForValue(shownCpi, Metric.CPI),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (speed != null) {
                        StatTile(
                            label = "Speed $season",
                            value = PaceAnalytics.format(speed, Metric.SPEED),
                            caption = when (speedSource) {
                                "live" -> "Live · calibrated"
                                "computed" -> "Calibrated"
                                else -> "Tennis Abstract"
                            },
                            color = accentForValue(speed, Metric.SPEED),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (publishedSpeed != null && speedSource != "tennisabstract") {
                        StatTile(
                            label = "TA $season",
                            value = PaceAnalytics.format(publishedSpeed, Metric.SPEED),
                            caption = "Published reference",
                            color = colors.textDim,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (shownCpi == null && speed == null) {
                    Text(
                        text = "No reading for $season.",
                        style = TextStyle(fontSize = 13.sp),
                        color = colors.textDim,
                    )
                }
                if (tournament.altitudeM != null) {
                    Spacer(Modifier.height(12.dp))
                    GlassNote(
                        "At ${tournament.altitudeM} m the ball travels through thinner air, so the court " +
                            "plays faster than its surface reading alone suggests. CPI has no altitude " +
                            "adjustment; the ace-based rating partly absorbs it.",
                    )
                }
            }

            if (cpiPoints.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(18.dp),
                ) {
                    SectionLabel("CPI history")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${cpiPoints.size} readings, ${cpiPoints.first().x.toInt()}–${cpiPoints.last().x.toInt()}",
                        style = TextStyle(fontSize = 11.5.sp),
                        color = colors.textFaint,
                    )
                    Spacer(Modifier.height(14.dp))
                    LineChart(
                        series = listOf(
                            ChartSeries(
                                label = "CPI",
                                color = accentForValue(cpiValues.average(), Metric.CPI),
                                points = cpiPoints,
                            ),
                        ),
                        xRange = 2012f..2026f,
                        yRange = chartRange(cpiValues, fallbackLow = 18f, fallbackHigh = 50f),
                        yLabel = { String.format(java.util.Locale.US, "%.0f", it) },
                        xLabel = { it.toInt().toString() },
                        referenceY = 34.5f,
                        referenceLabel = "Dashed line: middle of the medium band (34.5)",
                    )
                    Spacer(Modifier.height(10.dp))
                    SourceLine("Court Pace Index · courtspeed.com database")
                }
            }

            Spacer(Modifier.height(16.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                SectionLabel("By season")
                Spacer(Modifier.height(10.dp))
                val readings = data.seasonRows(tournament.id)
                if (readings.isEmpty()) {
                    Text("No readings.", style = TextStyle(fontSize = 13.sp), color = colors.textDim)
                } else {
                    readings.forEachIndexed { index, reading ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = reading.season.toString(),
                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                color = colors.textDim,
                                modifier = Modifier.width(52.dp),
                            )
                            Row(Modifier.width(96.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = reading.cpi?.let { PaceAnalytics.format(it, Metric.CPI) }
                                        ?: reading.speed?.let { PaceAnalytics.format(it, Metric.SPEED) }
                                        ?: "–",
                                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                    color = colors.text,
                                )
                                Spacer(Modifier.width(5.dp))
                                // CPI and speed are different scales; never let the reader
                                // assume a bare number is the same thing.
                                Text(
                                    text = if (reading.cpi != null) "CPI" else "SPD",
                                    style = TextStyle(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold),
                                    color = colors.textFaint,
                                )
                            }
                            Text(
                                text = buildString {
                                    reading.cpi?.let { append(PaceAnalytics.category(it).label) }
                                    reading.speed?.let {
                                        if (isNotEmpty()) append(" · ")
                                        append("speed ${PaceAnalytics.speedBand(it).label.lowercase()}")
                                        append(
                                            when (reading.speedSource) {
                                                "live" -> " · LIVE"
                                                "computed" -> " · CALC"
                                                else -> " · TA"
                                            },
                                        )
                                        if (reading.speedSource != "tennisabstract") {
                                            reading.publishedSpeed?.let { published ->
                                                append(" · TA ")
                                                append(PaceAnalytics.format(published, Metric.SPEED))
                                            }
                                        }
                                    }
                                },
                                style = TextStyle(fontSize = 11.5.sp),
                                color = colors.textDim,
                                modifier = Modifier.weight(1f),
                            )
                            reading.ace?.let {
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%.1f", it)}% aces",
                                    style = TextStyle(fontSize = 10.5.sp),
                                    color = colors.textFaint,
                                )
                            }
                        }
                        if (index < readings.lastIndex) HairLine()
                    }
                    if (readings.any { it.speedSource == "computed" || it.publishedSpeed != null }) {
                        Spacer(Modifier.height(12.dp))
                        SourceLine(
                            "CALC is the calibrated archive calculation. TA is the original published Tennis Abstract value, " +
                                "shown alongside CALC where both exist; TA-labelled rows are published-only fallbacks.",
                        )
                    }
                }
            }

            val divergence = PaceAnalytics.divergences(data, season).firstOrNull { it.tournament.id == tournament.id }
            if (divergence != null) {
                Spacer(Modifier.height(16.dp))
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(20.dp),
                ) {
                    SectionLabel("The two metrics")
                    Spacer(Modifier.height(10.dp))
                    StatRow("CPI percentile", "${(divergence.cpiPercentile * 100).toInt()}th")
                    HairLine()
                    StatRow("Ace-rate percentile", "${(divergence.speedPercentile * 100).toInt()}th")
                    Spacer(Modifier.height(12.dp))
                    GlassNote(
                        if (divergence.agrees) {
                            "The two metrics agree on this court: both place it in the same part of the " +
                                "field. That is the case you can trust most."
                        } else {
                            "${divergence.fasterBy} places this court faster than the other metric does, " +
                                "by ${(kotlin.math.abs(divergence.gap) * 100).toInt()} percentile points. " +
                                "That usually means the bounce and the way the ball actually travels through " +
                                "the air are telling different stories — altitude, heat, or ball choice."
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SourceLine(
                "CPI: courtspeed.com, centre-court readings from ATP / Tennis TV broadcast data. " +
                    "Speed: calibrated server/returner-adjusted calculation where available, with published Tennis Abstract values retained as references or labelled fallbacks.",
            )
            Spacer(Modifier.height(LocalTabBarInset.current))
        }
    }
}

private fun chartRange(values: List<Float>, fallbackLow: Float, fallbackHigh: Float): ClosedFloatingPointRange<Float> {
    if (values.isEmpty()) return fallbackLow..fallbackHigh
    val low = minOf(values.min(), fallbackLow)
    val high = maxOf(values.max(), fallbackHigh)
    return (low - 2f)..(high + 2f)
}
