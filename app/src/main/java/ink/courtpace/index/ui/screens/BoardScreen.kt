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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import ink.courtpace.index.core.analytics.PaceAnalytics
import ink.courtpace.index.core.analytics.Ranked
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.LiveEvent
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.Surface
import ink.courtpace.index.core.model.Tournament
import ink.courtpace.index.ui.components.AnimatedBar
import ink.courtpace.index.ui.components.DeltaBadge
import ink.courtpace.index.ui.components.MetricToggle
import ink.courtpace.index.ui.components.SeasonStepper
import ink.courtpace.index.ui.components.SourceLine
import ink.courtpace.index.ui.components.StatTile
import ink.courtpace.index.ui.components.ValueBadge
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.LocalTabBarInset
import ink.courtpace.index.ui.glass.HairLine
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.theme.PaceTheme
import ink.courtpace.index.ui.theme.accentFor
import ink.courtpace.index.ui.theme.accentForBand
import ink.courtpace.index.ui.theme.accentForSurface

fun accentForValue(value: Double, metric: Metric): Color = when (metric) {
    Metric.CPI -> accentFor(PaceAnalytics.category(value))
    Metric.SPEED -> accentForBand(PaceAnalytics.speedBand(value))
}

@Composable
fun BoardScreen(
    data: PaceData,
    season: Int,
    metric: Metric,
    surfaces: Set<Surface>,
    seasons: List<Int>,
    onSeason: (Int) -> Unit,
    onMetric: (Metric) -> Unit,
    onSurface: (Surface?) -> Unit,
    onOpen: (Tournament) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    statusNote: String?,
    liveAceByEvent: Map<String, ink.courtpace.index.core.live.AceRating.Result> = emptyMap(),
    liveProgress: String? = null,
    liveNote: String? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = PaceTheme.colors
    val ranking = PaceAnalytics.ranking(data, season, metric, surfaces)
    val top = ranking.firstOrNull()
    val bottom = ranking.lastOrNull()
    val average = PaceAnalytics.average(data, season, metric)

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
            text = "ATP TOUR · COURT SPEED",
            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
            color = colors.textFaint,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (metric) {
                    Metric.CPI -> "Court Pace Index"
                    Metric.SPEED -> "Surface speed"
                },
                style = TextStyle(
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.1).sp,
                ),
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            RefreshButton(refreshing = refreshing, onClick = onRefresh)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = when (metric) {
                Metric.CPI ->
                    "Physical bounce readings from Hawk-Eye, ${ranking.size} events in $season."
                Metric.SPEED ->
                    "Ace rate against a tour-average surface, ${ranking.size} events in $season."
            },
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.textDim,
        )

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SeasonStepper(seasons = seasons, selected = season, onChange = onSeason)
            MetricToggle(metric = metric, onChange = onMetric)
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip(text = "All", selected = surfaces.isEmpty(), onClick = { onSurface(null) })
            // Only surfaces with readings this season: a filter that can only return an
            // empty list is a dead end.
            data.surfacesPresent(season, metric).forEach { surface ->
                GlassChip(
                    text = surface.label,
                    selected = surface in surfaces,
                    onClick = { onSurface(surface) },
                )
            }
        }

        if (top != null && bottom != null && average != null) {
            Spacer(Modifier.height(18.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    StatTile(
                        label = "Fastest",
                        value = PaceAnalytics.format(top.value, metric),
                        caption = top.tournament.name,
                        color = accentForValue(top.value, metric),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Slowest",
                        value = PaceAnalytics.format(bottom.value, metric),
                        caption = bottom.tournament.name,
                        color = accentForValue(bottom.value, metric),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Field average",
                        value = PaceAnalytics.format(average, metric),
                        caption = "${ranking.size} events",
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (metric == Metric.SPEED) {
            val pending = data.liveEvents(season, metric)
            if (pending.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                SectionLabel(text = "Live now")
                Spacer(Modifier.height(10.dp))
                pending.forEach { (tournament, event) ->
                    val liveReading = data.liveReading(tournament.id, event.s)
                    LivePendingRow(
                        tournament = tournament,
                        event = event,
                        datasetRating = liveReading?.speed,
                        datasetMatches = liveReading?.matches,
                        pace = null,
                        ace = liveAceByEvent[tournament.id],
                        progress = liveProgress,
                        note = liveNote,
                        onClick = { onOpen(tournament) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                text = if (surfaces.isEmpty()) "Ranked" else surfaces.joinToString(" + ") { it.label } + " only",
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "tap for history",
                style = TextStyle(fontSize = 10.sp),
                color = colors.textFaint,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (ranking.isEmpty()) {
            GlassNote("No $season readings for this filter. Try another season or surface.")
        } else {
            val min = ranking.minOf { it.value }
            val max = ranking.maxOf { it.value }
            ranking.forEach { row ->
                RankRow(
                    row = row,
                    metric = metric,
                    min = min,
                    max = max,
                    live = data.isLive(row.tournament.id),
                    onClick = { onOpen(row.tournament) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (statusNote != null) {
            Spacer(Modifier.height(12.dp))
            GlassNote(statusNote)
        }

        Spacer(Modifier.height(16.dp))
        SourceLine(
            text = when (metric) {
                Metric.CPI -> "Court Pace Index from the courtspeed.com database, compiled from ATP and " +
                    "Tennis TV broadcast data. Centre-court readings."
                Metric.SPEED -> "Published ratings adjust for the servers and returners. Live ratings use " +
                    "completed-match ace rate against the 7.83% tour baseline; 1.00 = average."
            },
        )
        Spacer(Modifier.height(LocalTabBarInset.current))
    }
}

/** Small circular action that checks the project's published copy for a newer dataset. */
/** 2026-11-02 -> 2 Nov, so the row reads like prose rather than a timestamp. */
internal fun prettyDate(iso: String): String = runCatching {
    val date = java.time.LocalDate.parse(iso)
    "${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK)}"
}.getOrDefault(iso)

/** Red dot plus LIVE. Deliberately the only red in the interface. */
@Composable
internal fun LivePill(modifier: Modifier = Modifier) {
    val colors = PaceTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.danger.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(colors.danger))
        Spacer(Modifier.width(4.dp))
        Text(
            text = "LIVE",
            style = TextStyle(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
            color = colors.danger,
        )
    }
}

/**
 * An event being played right now that has no rating yet. It is listed rather than hidden,
 * and it says why there is no number instead of showing a placeholder that looks like data.
 */
@Composable
private fun LivePendingRow(
    tournament: Tournament,
    event: LiveEvent,
    datasetRating: Double?,
    datasetMatches: Int?,
    pace: ink.courtpace.index.core.live.AtpResultsParser.LivePace?,
    ace: ink.courtpace.index.core.live.AceRating.Result?,
    progress: String?,
    note: String?,
    onClick: () -> Unit,
) {
    val colors = PaceTheme.colors
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClickLabel = "Open ${tournament.name}") { onClick() },
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(14.dp),
        elevation = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(colors.danger.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.danger))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tournament.name,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    LivePill()
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(event.round ?: "In progress")
                        event.start?.let { start ->
                            append(" · since ")
                            append(prettyDate(start))
                        }
                        if (datasetRating != null) {
                            append(" · live speed ")
                            append(String.format(java.util.Locale.UK, "%.2f", datasetRating))
                            if (ace != null) {
                                append(" · ")
                                append(ace.matches)
                                append("/")
                                append(ace.discoveredMatches)
                                append(" completed")
                                if (ace.inProgressMatches > 0) {
                                    append(" · ")
                                    append(ace.inProgressMatches)
                                    append(" live excluded")
                                }
                                if (ace.missingMatchIds.isNotEmpty()) {
                                    append("\\nMissing: ")
                                    append(
                                        ace.missingMatchIds.take(2).joinToString(", ") { id ->
                                            val reason = ace.missingReasons[id].orEmpty()
                                            val shortReason = missingReasonLabel(reason)
                                            "$id ($shortReason)"
                                        },
                                    )
                                }
                            } else if (datasetMatches != null && datasetMatches > 0) {
                                append(" · ")
                                append(datasetMatches)
                                append(" matches")
                            }
                        } else if (ace != null) {
                            append(" · ")
                            append(ace.matches)
                            append(" matches · live speed ")
                            append(String.format(java.util.Locale.UK, "%.2f", ace.rating))
                        } else if (pace?.rating != null) {
                            append(" · ")
                            append(pace.matches)
                            append(" matches · live pace ")
                            append(String.format(java.util.Locale.UK, "%.2f", pace.rating))
                        } else if (pace != null) {
                            append(" · ")
                            append(pace.matches)
                            append(" matches · ")
                            append(String.format(java.util.Locale.UK, "%.0f", pace.tiebreakShare * 100))
                            append("% tie-break sets")
                        } else if (progress != null) {
                            append(" · ")
                            append(progress)
                        } else if (note != null) {
                            append(" · ")
                            append(note)
                        } else {
                            append(" · tap reload to read the ATP results page")
                        }
                    },
                    style = TextStyle(fontSize = 11.sp),
                    color = colors.textDim,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun missingReasonLabel(reason: String): String = when {
    reason.contains("HTTP 404", ignoreCase = true) -> "no feed record"
    reason.contains("HTTP 403", ignoreCase = true) -> "HTTP 403 after retry"
    reason.contains("HTTP 429", ignoreCase = true) -> "HTTP 429 rate limited"
    reason.contains("HTTP 5", ignoreCase = true) -> "server error after retry"
    reason.contains("timeout", ignoreCase = true) -> "timeout"
    reason.contains("setStats.set0", ignoreCase = true) -> "no full-match stats"
    reason.contains("padding", ignoreCase = true) ||
        reason.contains("cipher", ignoreCase = true) -> "decode failure"
    else -> reason.substringAfterLast(": ").take(34).ifBlank { "unavailable" }
}

@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    val colors = PaceTheme.colors
    val reduceMotion = ink.courtpace.index.ui.glass.rememberReduceMotion()
    val rotation by animateFloatAsState(
        targetValue = if (refreshing && !reduceMotion) 360f else 0f,
        animationSpec = if (refreshing && !reduceMotion) {
            infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart)
        } else {
            tween(220)
        },
        label = "refresh",
    )
    val tint = if (refreshing) colors.accent else colors.textDim
    Box(
        Modifier
            .size(48.dp)
            .clickable(
                enabled = !refreshing,
                onClickLabel = if (refreshing) "Checking for updates" else "Check for updates",
                role = Role.Button,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.6f), CircleShape),
        )
        Canvas(Modifier.size(18.dp).graphicsLayer { rotationZ = rotation }) {
            val w = size.width
            val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
            drawArc(
                color = tint,
                startAngle = 60f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(w * 0.12f, w * 0.12f),
                size = Size(w * 0.76f, w * 0.76f),
                style = stroke,
            )
            val tip = Offset(w * 0.88f, w * 0.34f)
            drawLine(tint, tip, Offset(tip.x - w * 0.16f, tip.y - w * 0.04f), w * 0.11f, StrokeCap.Round)
            drawLine(tint, tip, Offset(tip.x + w * 0.02f, tip.y + w * 0.16f), w * 0.11f, StrokeCap.Round)
        }
    }
}

@Composable
private fun RankRow(
    row: Ranked,
    metric: Metric,
    min: Double,
    max: Double,
    live: Boolean,
    onClick: () -> Unit,
) {
    val colors = PaceTheme.colors
    val accent = accentForValue(row.value, metric)
    val fraction = if (max > min) ((row.value - min) / (max - min)).toFloat() else 0.5f
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClickLabel = "Open ${row.tournament.name}") { onClick() },
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(14.dp),
        elevation = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (colors.isDark) 0.08f else 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.rank.toString(),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textDim,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.tournament.name,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    if (live) {
                        LivePill()
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(accentForSurface(row.tournament.surface), CircleShape),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${row.tournament.city} · ${row.tournament.surfaceLabel}",
                        style = TextStyle(fontSize = 11.sp),
                        color = colors.textFaint,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    DeltaBadge(delta = row.delta, metric = metric)
                }
                Spacer(Modifier.height(8.dp))
                AnimatedBar(fraction = fraction, color = accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = PaceAnalytics.format(row.value, metric),
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.8).sp,
                    ),
                    color = colors.text,
                )
                Spacer(Modifier.height(4.dp))
                ValueBadge(text = PaceAnalytics.label(row.value, metric), color = accent)
            }
        }
    }
}
