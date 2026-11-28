package ink.courtpace.index.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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
import ink.courtpace.index.ui.components.MetricToggle
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


internal data class CompareMenuEvent(
    val tournament: Tournament,
    val hasActiveReading: Boolean,
)

internal fun compareMenuEvents(
    selectedIds: List<String>,
    allTournaments: List<Tournament>,
    activeCandidates: List<Tournament>,
): List<CompareMenuEvent> {
    val candidatesById = activeCandidates.associateBy { it.id }
    val tournamentsById = allTournaments.associateBy { it.id }
    val selectedEvents = selectedIds.mapNotNull(tournamentsById::get)
    val remainingCandidates = activeCandidates.filterNot { it.id in selectedIds }
    return buildList {
        selectedEvents.forEach { tournament ->
            add(CompareMenuEvent(tournament, tournament.id in candidatesById))
        }
        remainingCandidates.forEach { tournament ->
            add(CompareMenuEvent(tournament, hasActiveReading = true))
        }
    }
}

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
    onOpen: (Tournament, Int) -> Unit,
) {
    val colors = PaceTheme.colors
    // Compare keeps an independent year selection; parent Pace navigation must not reset it.
    var compareSeason by remember { mutableIntStateOf(season) }
    var metric by remember { mutableStateOf(Metric.CPI) }
    var yearMenuExpanded by remember { mutableStateOf(false) }
    var tournamentMenuExpanded by remember { mutableStateOf(false) }

    val availableSeasons = data.seasons
        .filter { data.entries(it, metric).isNotEmpty() }
        .sortedDescending()
    val activeSeason = PaceAnalytics.nearestSeason(availableSeasons, compareSeason)
    val candidates = data.entries(activeSeason, metric).sortedBy { it.first.name }
    val menuEvents = compareMenuEvents(
        selectedIds = selected,
        allTournaments = data.tournaments,
        activeCandidates = candidates.map { it.first },
    )
    val fieldRanking = PaceAnalytics.ranking(data, activeSeason, metric)
    val rankById = fieldRanking.mapIndexed { index, row -> row.tournament.id to index + 1 }.toMap()
    val fieldValues = candidates.map { it.second }
    val fieldMin = fieldValues.minOrNull() ?: 0.0
    val fieldMax = fieldValues.maxOrNull() ?: 0.0

    val compared = selected.mapNotNull { id ->
        val tournament = data.tournament(id, activeSeason) ?: data.tournament(id)
            ?: return@mapNotNull null
        val value = data.value(id, activeSeason, metric) ?: return@mapNotNull null
        Triple(tournament, value, rankById[id] ?: 0)
    }.sortedBy { it.third.takeIf { rank -> rank > 0 } ?: Int.MAX_VALUE }

    val selectedEvents = selected.mapNotNull { data.tournament(it) }
    val historySeries = selectedEvents.mapIndexedNotNull { index, tournament ->
        val history = data.seasons.mapNotNull { year ->
            data.value(tournament.id, year, metric)?.let { ChartPoint(year.toFloat(), it.toFloat()) }
        }
        if (history.isEmpty()) {
            null
        } else {
            val sourceLabel = if (metric == Metric.SPEED) {
                data.seasons.mapNotNull { data.speedSource(tournament.id, it) }
                    .distinct()
                    .map { source ->
                        when (source) {
                            "computed" -> "CALC"
                            "live" -> "LIVE"
                            "tennisabstract" -> "TA"
                            else -> source.uppercase()
                        }
                    }
                    .joinToString("/")
            } else {
                ""
            }
            ChartSeries(
                label = if (sourceLabel.isBlank()) tournament.name else "${tournament.name} · $sourceLabel",
                color = seriesColors[index % seriesColors.size],
                points = history,
            )
        }
    }
    val historyValues = historySeries.flatMap { it.points }.map { it.y }
    val historyYears = historySeries.flatMap { it.points }.map { it.x }
    val historyMin = historyYears.minOrNull() ?: 2012f
    val historyMax = historyYears.maxOrNull() ?: 2026f
    val xRange = if (historyMin == historyMax) (historyMin - 0.5f)..(historyMax + 0.5f) else historyMin..historyMax
    val historyLow = historyValues.minOrNull() ?: 0f
    val historyHigh = historyValues.maxOrNull() ?: 1f
    val historySpread = (historyHigh - historyLow).coerceAtLeast(0f)
    val yPadding = maxOf(historySpread * 0.12f, if (metric == Metric.CPI) 2f else 0.025f)
    val divergences = PaceAnalytics.divergences(data, activeSeason)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
            text = "Compare court conditions",
            style = TextStyle(fontSize = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.0).sp),
            color = colors.text,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "Choose a season, switch between the two pace measures, and compare up to three tournaments side by side.",
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.textDim,
        )

        Spacer(Modifier.height(18.dp))
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(25.dp),
            intensity = 1.12f,
            elevation = 14.dp,
            contentPadding = PaddingValues(15.dp),
        ) {
            SectionLabel("MEASURE")
            Spacer(Modifier.height(9.dp))
            MetricToggle(metric = metric, onChange = { metric = it })
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    GlassChip(
                        text = "YEAR · $activeSeason",
                        selected = yearMenuExpanded,
                        onClick = { yearMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        selectionRole = Role.Button,
                    )
                    DropdownMenu(
                        expanded = yearMenuExpanded,
                        onDismissRequest = { yearMenuExpanded = false },
                        modifier = Modifier.heightIn(max = 340.dp),
                    ) {
                        availableSeasons.forEach { year ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(year.toString(), modifier = Modifier.weight(1f))
                                        if (year == activeSeason) Text("✓", color = colors.accent)
                                    }
                                },
                                onClick = {
                                    compareSeason = year
                                    yearMenuExpanded = false
                                },
                                modifier = Modifier.width(150.dp),
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    GlassChip(
                        text = "EVENTS · ${compared.size}/3 WITH DATA",
                        selected = tournamentMenuExpanded,
                        onClick = { tournamentMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        selectionRole = Role.Button,
                    )
                    DropdownMenu(
                        expanded = tournamentMenuExpanded,
                        onDismissRequest = { tournamentMenuExpanded = false },
                        modifier = Modifier.heightIn(max = 420.dp),
                    ) {
                        Text(
                            text = "SELECT UP TO THREE",
                            modifier = Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
                            color = colors.textFaint,
                        )
                        menuEvents.forEach { event ->
                            val tournament = event.tournament
                            val isSelected = tournament.id in selected
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = tournament.name,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = if (isSelected) "✓" else "＋",
                                                color = if (isSelected) colors.accent else colors.textFaint,
                                            )
                                        }
                                        if (!event.hasActiveReading) {
                                            Text(
                                                text = "No ${metric.short} reading in $activeSeason",
                                                style = TextStyle(fontSize = 10.sp),
                                                color = colors.textFaint,
                                            )
                                        }
                                    }
                                },
                                onClick = { onToggle(tournament.id) },
                                // Keep unavailable selected events enabled so they can be removed.
                                enabled = isSelected || selected.size < 3,
                                modifier = Modifier.width(260.dp),
                            )
                        }
                        if (selected.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Clear selected events", color = colors.textDim) },
                                onClick = { selected.toList().forEach(onToggle) },
                                modifier = Modifier.width(260.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = "Higher readings indicate faster conditions. Your event selections stay in place when you switch years or measures; unavailable readings are skipped.",
                style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.textFaint,
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("${metric.short} comparison · $activeSeason")
        Spacer(Modifier.height(5.dp))
        Text(
            text = when (metric) {
                Metric.CPI -> "Physical court-bounce readings, ranked against every CPI reading in $activeSeason."
                Metric.SPEED -> "Ace-based speed ratings, ranked against every available Speed reading in $activeSeason."
            },
            style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
            color = colors.textDim,
        )
        Spacer(Modifier.height(10.dp))

        if (compared.isEmpty()) {
            GlassNote(
                if (selected.isEmpty()) {
                    "Choose tournaments from the Events control to build your comparison."
                } else {
                    "None of the selected tournaments has a ${metric.short} reading in $activeSeason. Choose another year or add an event with data."
                },
            )
        } else {
            compared.forEachIndexed { index, (tournament, value, rank) ->
                val accent = metricAccent(value, metric)
                val progress = if (fieldMax > fieldMin) {
                    ((value - fieldMin) / (fieldMax - fieldMin)).toFloat().coerceIn(0.04f, 1f)
                } else {
                    0.72f
                }
                val source = if (metric == Metric.SPEED) {
                    when (data.speedSource(tournament.id, activeSeason)) {
                        "computed" -> "CALC"
                        "live" -> "LIVE"
                        "tennisabstract" -> "TA-only"
                        else -> "Speed source unavailable"
                    }
                } else {
                    "Hawk-Eye CPI"
                }
                val reading = data.reading(tournament.id, activeSeason, metric)
                val previousSeason = PaceAnalytics.previousSeason(data, tournament.id, activeSeason, metric)
                val previousValue = PaceAnalytics.previousValue(data, tournament.id, activeSeason, metric)
                val change = if (previousSeason != null && previousValue != null) {
                    val delta = value - previousValue
                    "${if (delta >= 0) "+" else ""}${PaceAnalytics.format(delta, metric)} vs $previousSeason"
                } else {
                    "No earlier like-for-like reading"
                }
                CompareContentSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 9.dp)
                        .clickable { onOpen(tournament, activeSeason) },
                    contentPadding = PaddingValues(15.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .background(accent.copy(alpha = if (colors.isDark) 0.20f else 0.14f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                color = accent,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = tournament.name,
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                color = colors.text,
                            )
                            Text(
                                text = "${tournament.surfaceLabel} · ${tournament.level.short} · $source",
                                style = TextStyle(fontSize = 10.5.sp),
                                color = colors.textFaint,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = PaceAnalytics.format(value, metric),
                                style = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold),
                                color = accent,
                            )
                            Text(
                                text = if (rank > 0) "#$rank / ${fieldRanking.size}" else "Unranked",
                                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                color = colors.textDim,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.48f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(accent),
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = change,
                            style = TextStyle(fontSize = 10.5.sp),
                            color = colors.textDim,
                            modifier = Modifier.weight(1f),
                        )
                        if (reading?.matches != null && metric == Metric.SPEED) {
                            Text(
                                text = "${reading.matches} matches",
                                style = TextStyle(fontSize = 10.5.sp),
                                color = colors.textFaint,
                            )
                        }
                    }
                }
            }
            SourceLine(
                if (metric == Metric.SPEED) {
                    "Speed source is labelled per event: CALC is the archived calibrated calculation, LIVE is provisional, and TA-only is the published Tennis Abstract fallback."
                } else {
                    "CPI is a physical bounce measurement. A higher CPI places the court in a faster pace band."
                },
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Selected events · historical trend")
        Spacer(Modifier.height(5.dp))
        Text(
            text = "Follow the selected tournaments across every season where this measure has a reading.",
            style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
            color = colors.textDim,
        )
        Spacer(Modifier.height(10.dp))
        if (historySeries.isEmpty()) {
            GlassNote("No historical readings are available for the selected events and measure.")
        } else {
            CompareContentSurface(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                LineChart(
                    series = historySeries,
                    xRange = xRange,
                    yRange = (historyLow - yPadding)..(historyHigh + yPadding),
                    yLabel = { PaceAnalytics.format(it.toDouble(), metric) },
                    xLabel = { it.toInt().toString() },
                    chartHeight = 205.dp,
                )
                Spacer(Modifier.height(10.dp))
                ChartLegend(historySeries.map { it.label to it.color })
                Spacer(Modifier.height(8.dp))
                SourceLine(
                    if (metric == Metric.SPEED) {
                        "A tournament's historical series may change source over time; the legend indicates the available source types across its readings."
                    } else {
                        "Gaps represent seasons without a physical CPI reading; the line is not drawn across missing seasons."
                    },
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("CPI vs Speed · $activeSeason")
        Spacer(Modifier.height(5.dp))
        Text(
            text = "These measures describe different things. Percentile positions make their relative rankings comparable without mixing their units.",
            style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
            color = colors.textDim,
        )
        Spacer(Modifier.height(10.dp))
        CompareContentSurface(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DotKey("CPI", metricAccent(35.0, Metric.CPI))
                DotKey("Speed", metricAccent(1.0, Metric.SPEED))
            }
            Spacer(Modifier.height(10.dp))
            if (divergences.isEmpty()) {
                Text(
                    text = "Fewer than two events have both CPI and Speed readings in $activeSeason.",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    color = colors.textDim,
                )
            } else {
                if (divergences.none { !it.agrees }) {
                    Text(
                        text = "In $activeSeason the two measures broadly agree: no event is placed in a meaningfully different part of the field by CPI versus Speed.",
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
                        leftColor = metricAccent(divergence.cpi, Metric.CPI),
                        rightColor = metricAccent(divergence.speed, Metric.SPEED),
                    )
                }
                Spacer(Modifier.height(7.dp))
                SourceLine(
                    "Percentiles are calculated within the paired-event cohort for each measure. A wide gap means bounce readings and ace-rate behaviour rank that event differently.",
                )
            }
        }

        if (selectedEvents.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionLabel("Open event details")
            Spacer(Modifier.height(9.dp))
            selectedEvents.forEach { tournament ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(tournament, activeSeason) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = tournament.name,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = colors.text,
                        )
                        Text(
                            text = tournament.location,
                            style = TextStyle(fontSize = 10.5.sp),
                            color = colors.textFaint,
                        )
                    }
                    Text("VIEW", style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = colors.accent)
                }
            }
        }

        Spacer(Modifier.height(LocalTabBarInset.current))
    }
}


@Composable
private fun CompareContentSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PaceTheme.colors
    val shape = RoundedCornerShape(22.dp)
    val fill = if (colors.isDark) {
        Color.White.copy(alpha = 0.055f)
    } else {
        Color.White.copy(alpha = 0.76f)
    }
    val rim = Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.88f)
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, rim, shape)
            .padding(contentPadding),
        content = content,
    )
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
