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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.model.CourtRecord
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.theme.PaceTheme
import ink.courtpace.index.ui.theme.accentFor

@Composable
fun CourtsScreen(
    courts: List<CourtRecord>,
    onOpen: (CourtRecord) -> Unit,
    onStartTest: () -> Unit,
) {
    val colors = PaceTheme.colors
    var query by remember { mutableStateOf("") }
    val filtered = remember(courts, query) {
        if (query.isBlank()) courts else courts.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.surface.label.contains(query, ignoreCase = true) ||
                it.locationLabel.contains(query, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "COURTS",
                style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
                color = colors.textFaint,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${courts.size} measured",
                style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.1).sp),
                color = colors.text,
            )
            Spacer(Modifier.height(16.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
                elevation = 10.dp,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.5.sp, color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (query.isEmpty()) "Search courts" else "",
                                style = TextStyle(fontSize = 14.5.sp),
                                color = colors.textFaint,
                            )
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (filtered.isEmpty()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(22.dp),
                ) {
                    Text(
                        text = if (courts.isEmpty()) "Nothing measured yet" else "No match",
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (courts.isEmpty()) {
                            "A saved court keeps the whole measurement: both coefficients, the " +
                                "drop height, the ball, the temperature and the confidence."
                        } else {
                            "Try a different name or surface."
                        },
                        style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                        color = colors.textDim,
                    )
                    Spacer(Modifier.height(16.dp))
                    ink.courtpace.index.ui.glass.GlassButton(
                        text = "Start a test",
                        onClick = onStartTest,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 130.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.id }) { record ->
                    CourtRow(record = record, onClick = { onOpen(record) })
                }
            }
        }
    }
}

@Composable
private fun CourtRow(record: CourtRecord, onClick: () -> Unit) {
    val colors = PaceTheme.colors
    val accent = accentFor(record.category)
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = record.cpi.toInt().toString(),
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
                    color = accent,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = record.name,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.text,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${record.surface.shortLabel} · ${record.category.label} · CPR ${record.cpr.oneDecimal()}",
                    style = TextStyle(fontSize = 12.sp),
                    color = colors.textDim,
                    maxLines = 1,
                )
            }
            Text(
                text = record.createdAt.asShortDate(),
                style = TextStyle(fontSize = 11.sp),
                color = colors.textFaint,
            )
        }
    }
}

@Composable
fun MethodScreen() {
    val colors = PaceTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "METHOD",
            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
            color = colors.textFaint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "What this number is",
            style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.1).sp),
            color = colors.text,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The ITF reference test fires a ball at 30 m/s and 16° from an air cannon through " +
                "infrared gates. That is not a phone's job. Court Pace measures the same two " +
                "coefficients a different way and recombines them with the published equation.",
            style = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
            color = colors.textDim,
        )

        Spacer(Modifier.height(22.dp))
        SectionCard("1 · Restitution from flight time") {
            Text(
                text = "The ball is released from a measured height with no spin. Impact speed is " +
                    "√(2gh). If the surface returns it with restitution e, it leaves at e·√(2gh) and " +
                    "returns to the ground after Δt = 2·e·√(2gh)/g. Invert that and the microphone's " +
                    "timing becomes the coefficient:",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
            Spacer(Modifier.height(10.dp))
            FormulaBox("e = g·Δt / (2·√(2gh))    and    e = √(h_rebound / h_drop)")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Each pair of consecutive impacts gives one estimate; the app averages them " +
                    "and reports the spread, which is what the confidence figure is built from.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionCard("2 · Friction from the slide") {
            Text(
                text = "A flat body sliding without other forces decelerates at μg, so the phone's own " +
                    "accelerometer reads the coefficient directly: μ = a/g. The push impulse and the " +
                    "final stutter are trimmed from both ends of the slide.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionCard("3 · Combining them the ITF way") {
            FormulaBox("CPR = 100·(1 − μ) + 150·(0.81 − e₂₃)")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "0.81 is the ITF mean restitution across all surfaces and 150 the pace " +
                    "perception constant. e₂₃ is the measured restitution corrected to 23 °C with the " +
                    "ITF coefficient 0.003 per °C: e₂₃ = e + 0.003·(23 − T).",
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.textDim,
            )
            Spacer(Modifier.height(10.dp))
            FormulaBox("Category 1 slow ≤ 29 · 2 medium-slow 30-34 · 3 medium 35-39 · 4 medium-fast 40-44 · 5 fast ≥ 45")
        }

        Spacer(Modifier.height(22.dp))
        Text(
            text = "Data the measurement needs",
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
            color = colors.text,
        )
        Spacer(Modifier.height(12.dp))
        DataRow(
            "Drop height",
            "254 cm per ITF TB 04/01 (127 cm accepted for the predictive rebound method).",
        )
        DataRow(
            "Impact timings",
            "Microphone, 44.1 kHz mono, peak envelope with an adaptive threshold.",
        )
        DataRow(
            "Surface family",
            "From the court operator or the ITF classified-surface list; sets the fallback friction and the sanity range.",
        )
        DataRow(
            "Ball type",
            "ITF Type 1 / 2 / 3 or high altitude, from the printed can. Governs expected rebound.",
        )
        DataRow(
            "Ball and air temperature",
            "Thermometer, or the venue's reading; drives the restitution correction.",
        )
        DataRow(
            "Slide deceleration",
            "Accelerometer while the phone glides on the court.",
        )
        DataRow(
            "Court identity",
            "Name, indoor or outdoor, location note, date - the record you compare against later.",
        )

        Spacer(Modifier.height(22.dp))
        Text(
            text = "Sources",
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
            color = colors.text,
        )
        Spacer(Modifier.height(10.dp))
        SourceRow("ITF Technical Booklet - Court pace (ITF CS 01/02), ball rebound (ITF TB 04/01), Tables 2 and 3.")
        SourceRow("ITF Rules of Tennis, Appendix I - ball types, mass, size and rebound.")
        SourceRow("Cross, R. (2002) Measurements of the horizontal and vertical speeds of tennis courts, Sports Engineering.")
        SourceRow("Cross, R. Measurement of the speed and bounce of tennis courts - simple COF and COR methods.")

        Spacer(Modifier.height(16.dp))
        GlassNote(
            "Limits, stated plainly: a phone cannot reproduce a 30 m/s impact, so friction is measured " +
                "at sliding speed rather than at impact speed, and restitution at a vertical drop rather " +
                "than at 16°. Treat the result as a well-grounded estimate that separates courts " +
                "reliably, not as a certification.",
        )
        Spacer(Modifier.height(130.dp))
    }
}

@Composable
private fun SectionCard(title: String, body: @Composable () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(18.dp),
    ) {
        Text(
            text = title,
            style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
            color = PaceTheme.colors.text,
        )
        Spacer(Modifier.height(8.dp))
        body()
    }
}

@Composable
private fun FormulaBox(text: String) {
    val colors = PaceTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.55f), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.text,
        )
    }
}

@Composable
private fun DataRow(title: String, body: String) {
    val colors = PaceTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .background(colors.accent, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
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
private fun SourceRow(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        color = PaceTheme.colors.textDim,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}


