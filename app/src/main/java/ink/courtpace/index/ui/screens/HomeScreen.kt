package ink.courtpace.index.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.model.CourtRecord
import ink.courtpace.index.core.physics.PaceResult
import ink.courtpace.index.ui.components.CategoryStrip
import ink.courtpace.index.ui.components.IndexDial
import ink.courtpace.index.ui.glass.GlassButton
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.theme.PaceTheme
import ink.courtpace.index.ui.theme.accentFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    courts: List<CourtRecord>,
    latest: CourtRecord?,
    onStartTest: () -> Unit,
    onOpenCourt: (CourtRecord) -> Unit,
    onOpenMethod: () -> Unit,
) {
    val colors = PaceTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "COURT PACE INDEX",
                    style = TextStyle(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                    ),
                    color = colors.textFaint,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (latest != null) latest.name else "Measure the court",
                    style = TextStyle(
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1.1).sp,
                    ),
                    color = colors.text,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (latest != null) {
                "${latest.surface.label} · measured ${latest.createdAt.asShortDate()}"
            } else {
                "Two phone measurements, one ITF-style pace rating."
            },
            style = TextStyle(fontSize = 13.5.sp),
            color = colors.textDim,
        )

        Spacer(Modifier.height(22.dp))

        if (latest != null) {
            val category = latest.category
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = category.displayName,
                        style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                        color = accentFor(category),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(latest.confidence * 100).toInt()}% confidence",
                        style = TextStyle(fontSize = 11.5.sp),
                        color = colors.textFaint,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    IndexDial(
                        index = latest.cpi.toFloat(),
                        cpr = latest.cpr,
                        category = category,
                        diameter = 210.dp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = category.blurb,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                    color = colors.textDim,
                )
                Spacer(Modifier.height(14.dp))
                CategoryStrip(cpr = latest.cpr)
            }
        } else {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            ) {
                Text(
                    text = "No courts yet",
                    style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.text,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Drop a ball from 254 cm, listen to the bounce, slide the phone once. " +
                        "You get the coefficient of restitution, the coefficient of friction and the " +
                        "Court Pace Rating they combine into.",
                    style = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
                    color = colors.textDim,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        GlassButton(
            text = "Start a test",
            onClick = onStartTest,
            modifier = Modifier.fillMaxWidth(),
        )

        if (courts.size > 1) {
            Spacer(Modifier.height(28.dp))
            SectionLabel("Recent courts")
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(courts.take(8), key = { it.id }) { record ->
                    RecentCard(record = record, onClick = { onOpenCourt(record) })
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("The protocol")
        Spacer(Modifier.height(12.dp))
        StepRow("1", "Drop", "Ball released from a measured height onto the court, phone flat beside the landing spot.")
        Spacer(Modifier.height(10.dp))
        StepRow("2", "Listen", "The microphone times the impacts. Flight time gives the coefficient of restitution.")
        Spacer(Modifier.height(10.dp))
        StepRow("3", "Slide", "A firm push sends the phone gliding; deceleration gives the coefficient of friction.")

        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable { onOpenMethod() }
                .background(Color.White.copy(alpha = if (colors.isDark) 0.05f else 0.5f), RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Column {
                Text(
                    text = "Read the method and the data behind it",
                    style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.text,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "ITF CS 01/02 formulas, required inputs and their sources",
                    style = TextStyle(fontSize = 12.sp),
                    color = colors.textDim,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        GlassNote(
            "Court Pace is a predictive estimate. It is not an ITF classification and does not " +
                "replace the air-cannon reference test.",
        )
        Spacer(Modifier.height(130.dp))
    }
}

@Composable
private fun RecentCard(record: CourtRecord, onClick: () -> Unit) {
    val colors = PaceTheme.colors
    val accent = accentFor(record.category)
    GlassSurface(
        modifier = Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(28.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Text(
            text = record.name,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = colors.text,
            maxLines = 2,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = record.cpi.toInt().toString(),
                style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.4).sp),
                color = colors.text,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "CPI",
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
                color = colors.textFaint,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(accent, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                text = record.category.label,
                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                color = accent,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = record.surface.shortLabel,
            style = TextStyle(fontSize = 11.sp),
            color = colors.textFaint,
        )
    }
}

@Composable
private fun StepRow(number: String, title: String, body: String) {
    val colors = PaceTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = colors.accent,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = colors.textDim,
            )
        }
    }
}

@Composable
fun PaceResultHeader(pace: PaceResult, modifier: Modifier = Modifier) {
    val colors = PaceTheme.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IndexDial(
            index = pace.cpi.toFloat(),
            cpr = pace.cpr,
            category = pace.category,
            diameter = 244.dp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = pace.category.displayName,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = accentFor(pace.category),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = pace.category.blurb,
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = colors.textDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

internal fun Long.asShortDate(): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(this))
