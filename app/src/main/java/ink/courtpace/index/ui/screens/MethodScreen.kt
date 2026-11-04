package ink.courtpace.index.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.analytics.PaceAnalytics
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.core.model.PaceCategory
import ink.courtpace.index.core.model.SpeedBand
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.HairLine
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.glass.StatRow
import ink.courtpace.index.ui.theme.PaceTheme
import ink.courtpace.index.ui.theme.accentFor
import ink.courtpace.index.ui.theme.accentForBand

@Composable
fun MethodScreen(data: PaceData) {
    val colors = PaceTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "METHOD & SOURCES",
            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
            color = colors.textFaint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Two ways to measure a court",
            style = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            color = colors.text,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The ATP does not publish a downloadable court pace feed. What exists publicly is two " +
                "different kinds of measurement, made by different people for different reasons. This app " +
                "carries both and shows where they part company.",
            style = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
            color = colors.textDim,
        )

        Spacer(Modifier.height(22.dp))
        MethodCard("1 · Court Pace Index", accentFor(PaceCategory.MEDIUM)) {
            Text(
                text = "A physical reading of the bounce, derived from Hawk-Eye tracking on the show " +
                    "courts. Friction takes pace off the ball horizontally; restitution decides how high " +
                    "it comes back. The published combination is:",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
            Spacer(Modifier.height(10.dp))
            FormulaBox("CPI = 100(1 − μ) + 150(0.81 − e)")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "μ is the coefficient of friction, e the coefficient of restitution, 0.81 the mean " +
                    "restitution across all surfaces and 150 a pace-perception constant. Higher means " +
                    "faster: less grip, or a lower bounce.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "It is measured on a handful of show courts, only where Hawk-Eye is installed, so " +
                    "outer courts and qualifying carry no reading. It also drifts upward through a " +
                    "tournament as shoe rubber wears the grit off the surface.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
        }

        Spacer(Modifier.height(14.dp))
        MethodCard("2 · Surface speed", accentForBand(SpeedBand.MEDIUM)) {
            Text(
                text = "A behavioural reading. Count the aces at an event, adjust for which servers and " +
                    "which returners were actually on court, and compare with what those same players " +
                    "would have produced on a tour-average surface. 1.00 is average; the usual range " +
                    "across ATP events is about 0.50 to 1.50.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Because it is behavioural it absorbs everything else that changed — heat, humidity, " +
                    "wind, altitude, the balls — not just the paint. It covers every tour-level event, but " +
                    "it needs matches to have been played, so small draws are noisy.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
        }

        Spacer(Modifier.height(14.dp))
        MethodCard("3 · Why they disagree", colors.accent) {
            Text(
                text = "One measures the surface; the other measures the tennis. A court can be physically " +
                    "slow and still give up aces if the air is thin or the balls fly. The app puts both on a " +
                    "percentile scale within the same season and shows the distance between them, because " +
                    "that distance is the interesting part — it is where altitude, indoor air and ball " +
                    "choice show up.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Bands")
        Spacer(Modifier.height(10.dp))
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            PaceCategory.entries.forEach { category ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(accentFor(category), CircleShape))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = category.label,
                        style = TextStyle(fontSize = 12.5.sp),
                        color = colors.textDim,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (category == PaceCategory.FAST) "45+" else "${category.lower.toInt()}–${category.upper.toInt()}",
                        style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "CPI bands follow the ITF pace categories. The speed bands are ours: the ace-based " +
                    "scale is sliced evenly around its 1.00 average.",
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
                color = colors.textFaint,
            )
            Spacer(Modifier.height(8.dp))
            SpeedBand.entries.forEach { band ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(accentForBand(band), CircleShape))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = band.label,
                        style = TextStyle(fontSize = 12.sp),
                        color = colors.textDim,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = when (band) {
                            SpeedBand.VERY_SLOW -> "< 0.75"
                            SpeedBand.SLOW -> "0.75–0.92"
                            SpeedBand.MEDIUM -> "0.92–1.08"
                            SpeedBand.FAST -> "1.08–1.25"
                            SpeedBand.VERY_FAST -> "1.25+"
                        },
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        color = colors.text,
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Data")
        Spacer(Modifier.height(10.dp))
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            StatRow("Dataset built", data.dataset.meta.generated)
            HairLine()
            StatRow("Events", data.tournaments.size.toString())
            HairLine()
            StatRow("CPI coverage", PaceAnalytics.coverage(data, Metric.CPI))
            HairLine()
            StatRow("Speed coverage", PaceAnalytics.coverage(data, Metric.SPEED))
            HairLine()
            StatRow("Events with both in 2026", PaceAnalytics.divergences(data, data.latestSeason).size.toString())
        }

        Spacer(Modifier.height(14.dp))
        data.dataset.meta.sources.forEach { source ->
            GlassSurface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Text(
                    text = source.name,
                    style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.text,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = source.detail,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    color = colors.textDim,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = source.url,
                    style = TextStyle(fontSize = 11.sp),
                    color = colors.accent,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionLabel("Known gaps")
        Spacer(Modifier.height(10.dp))
        GapRow("COVID seasons", "2020 and parts of 2021 were cancelled; those years simply have no reading.")
        GapRow("Show courts only", "CPI covers the courts with Hawk-Eye, so early rounds on outer courts are unmeasured.")
        GapRow("Roland Garros 2026", "No ace-based speed reading for the event in the current season's list.")
        GapRow("Grand Slam history", "CPI history for the majors is thin before 2016; the ace-based rating goes back to 1991.")
        GapRow("No altitude field", "Altitude is stored for a few venues only, and neither metric adjusts for it.")

        Spacer(Modifier.height(16.dp))
        GlassNote(
            "Speed figures for 2012-2025 are recomputed by this project from the public ATP match " +
                "archive compiled by Jeff Sackmann (CC BY-NC-SA 4.0), using the published method and " +
                "normalised per season; 2025-26 readings published by Tennis Abstract take precedence " +
                "where they exist. The two agree closely - correlation 0.93, mean absolute difference " +
                "0.10 - but they are not the same series, and the app dims the computed seasons " +
                "wherever it shows them.",
        )
        Spacer(Modifier.height(12.dp))
        GlassNote(
            "Court Pace is an independent reader of public data. It is not affiliated with, endorsed by " +
                "or connected to the ATP or the ITF, and the values are reproductions of published " +
                "measurements, not new measurements of our own.",
        )
        Spacer(Modifier.height(130.dp))
    }
}

@Composable
private fun MethodCard(
    title: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    val colors = PaceTheme.colors
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(
                text = title,
                style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun FormulaBox(text: String) {
    val colors = PaceTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.55f),
                RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
            color = colors.text,
        )
    }
}

@Composable
private fun GapRow(title: String, body: String) {
    val colors = PaceTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .background(colors.textFaint, CircleShape),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.textDim,
            )
        }
    }
}
