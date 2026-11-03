package ink.courtpace.index.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ink.courtpace.index.core.model.BallType
import ink.courtpace.index.core.model.CourtRecord
import ink.courtpace.index.core.model.SurfaceType
import ink.courtpace.index.core.physics.BounceResult
import ink.courtpace.index.core.physics.PaceEngine
import ink.courtpace.index.core.physics.PaceResult
import ink.courtpace.index.core.physics.SlideResult
import ink.courtpace.index.ui.MeasurePhase
import ink.courtpace.index.ui.TestDraft
import ink.courtpace.index.ui.components.CoefficientBar
import ink.courtpace.index.ui.components.ConfidenceMeter
import ink.courtpace.index.ui.glass.GlassButton
import ink.courtpace.index.ui.glass.GlassChip
import ink.courtpace.index.ui.glass.GlassNote
import ink.courtpace.index.ui.glass.GlassSurface
import ink.courtpace.index.ui.glass.HairLine
import ink.courtpace.index.ui.glass.SectionLabel
import ink.courtpace.index.ui.glass.StatRow
import ink.courtpace.index.ui.theme.PaceTheme
import ink.courtpace.index.ui.theme.accentFor
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FlowHeader(
    step: String,
    title: String,
    onBack: () -> Unit,
) {
    val colors = PaceTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.6f), CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "\u2039",
                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
                color = colors.text,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = step.uppercase(),
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                ),
                color = colors.textFaint,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp,
                ),
                color = colors.text,
            )
        }
    }
}

@Composable
fun SetupScreen(
    draft: TestDraft,
    onDraftChange: ((TestDraft) -> TestDraft) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val colors = PaceTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        FlowHeader(step = "Step 1 of 3", title = "Court and ball", onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(6.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                SectionLabel("Court name")
                Spacer(Modifier.height(10.dp))
                BasicTextField(
                    value = draft.name,
                    onValueChange = { value -> onDraftChange { it.copy(name = value) } },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (draft.name.isEmpty()) {
                                Text(
                                    text = "Club, court number, anything you will recognise",
                                    style = TextStyle(fontSize = 15.sp),
                                    color = colors.textFaint,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                HairLine()
                Spacer(Modifier.height(12.dp))
                SectionLabel("Location note")
                Spacer(Modifier.height(10.dp))
                BasicTextField(
                    value = draft.location,
                    onValueChange = { value -> onDraftChange { it.copy(location = value) } },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (draft.location.isEmpty()) {
                                Text(
                                    text = "City, club, court side",
                                    style = TextStyle(fontSize = 14.sp),
                                    color = colors.textFaint,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Surface")
            Spacer(Modifier.height(10.dp))
            SurfaceType.entries.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { surface ->
                        GlassChip(
                            text = surface.shortLabel,
                            selected = draft.surface == surface,
                            onClick = { onDraftChange { it.copy(surface = surface) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = draft.surface.note,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.textFaint,
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("Setting")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(
                    text = "Outdoor",
                    selected = !draft.indoor,
                    onClick = { onDraftChange { it.copy(indoor = false) } },
                )
                GlassChip(
                    text = "Indoor",
                    selected = draft.indoor,
                    onClick = { onDraftChange { it.copy(indoor = true) } },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Ball")
            Spacer(Modifier.height(10.dp))
            BallType.entries.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { ball ->
                        GlassChip(
                            text = ball.label,
                            selected = draft.ballType == ball,
                            onClick = { onDraftChange { it.copy(ballType = ball) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Drop height")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropChip("254 cm · ITF", 254.0, draft, onDraftChange)
                DropChip("200 cm", 200.0, draft, onDraftChange)
                DropChip("127 cm · quick", 127.0, draft, onDraftChange)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "254 cm is the ITF ball-rebound drop (ITF TB 04/01). Shorter drops are easier " +
                    "to hold steady but amplify timing error.",
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.textFaint,
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel("Ball and air temperature")
            Spacer(Modifier.height(10.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${draft.temperatureC.oneDecimal()} °C",
                            style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
                            color = colors.text,
                        )
                        Text(
                            text = "Corrected COR uses 0.003 per °C from 23 °C",
                            style = TextStyle(fontSize = 11.5.sp),
                            color = colors.textFaint,
                        )
                    }
                    StepperButton("−") {
                        onDraftChange { it.copy(temperatureC = (it.temperatureC - 0.5).coerceAtLeast(0.0)) }
                    }
                    Spacer(Modifier.width(10.dp))
                    StepperButton("+") {
                        onDraftChange { it.copy(temperatureC = (it.temperatureC + 0.5).coerceAtMost(50.0)) }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            GlassButton(
                text = "Continue to bounce test",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
private fun DropChip(
    label: String,
    value: Double,
    draft: TestDraft,
    onDraftChange: ((TestDraft) -> TestDraft) -> Unit,
) {
    GlassChip(
        text = label,
        selected = draft.dropHeightCm == value,
        onClick = { onDraftChange { it.copy(dropHeightCm = value) } },
    )
}

@Composable
private fun StepperButton(symbol: String, onClick: () -> Unit) {
    val colors = PaceTheme.colors
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.65f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            color = colors.text,
        )
    }
}

@Composable
fun BounceScreen(
    draft: TestDraft,
    phase: MeasurePhase,
    amplitude: Float,
    bounce: BounceResult?,
    impactMarkers: List<Float>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = PaceTheme.colors
    val context = LocalContext.current
    val running = phase is MeasurePhase.Running

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onStart() }

    fun begin() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onStart() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize()) {
        FlowHeader(step = "Step 2 of 3", title = "Bounce test", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Drop the ball from ${draft.dropHeightCm.toInt()} cm onto the court. " +
                    "Phone flat on the ground, an arm's length from the landing spot.",
                style = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
                color = colors.textDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            ListeningRing(
                amplitude = amplitude,
                running = running,
                markers = impactMarkers,
                accent = colors.accent,
            )

            Spacer(Modifier.height(18.dp))
            when (phase) {
                is MeasurePhase.Running -> Text(
                    text = phase.hint,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = colors.text,
                    textAlign = TextAlign.Center,
                )

                is MeasurePhase.Failed -> GlassNote(phase.message)
                else -> Text(
                    text = if (bounce == null) "Ready when you are" else "Bounce captured",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = colors.text,
                )
            }

            if (bounce != null) {
                Spacer(Modifier.height(16.dp))
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    contentPadding = PaddingValues(18.dp),
                ) {
                    StatRow("Impacts heard", "${bounce.impacts}")
                    HairLine()
                    StatRow("Rebound height", "${bounce.reboundHeightCm.roundInt()} cm", emphasis = true)
                    HairLine()
                    StatRow("Coefficient of restitution", bounce.cor.threeDecimal(), emphasis = true)
                    HairLine()
                    StatRow("Bounce-to-bounce spread", bounce.spread.threeDecimal())
                }
            }

            Spacer(Modifier.height(20.dp))
            if (running) {
                GlassButton(text = "Stop listening", onClick = onStop, prominent = false, modifier = Modifier.fillMaxWidth())
            } else {
                GlassButton(
                    text = if (bounce == null) "Start listening" else "Measure again",
                    onClick = { begin() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (bounce != null) {
                Spacer(Modifier.height(10.dp))
                GlassButton(
                    text = "Continue to friction test",
                    onClick = onContinue,
                    prominent = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            GlassNote(
                "Hold the phone still while the ball bounces. Two clean impacts are enough for a " +
                    "reading; four give the confidence figure something to stand on.",
            )
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
private fun ListeningRing(
    amplitude: Float,
    running: Boolean,
    markers: List<Float>,
    accent: Color,
) {
    val colors = PaceTheme.colors
    val transition = rememberInfiniteTransition(label = "ring")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    Canvas(Modifier.size(230.dp)) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = Color.White.copy(alpha = if (colors.isDark) 0.05f else 0.5f),
            radius = radius * 0.86f,
            center = center,
        )
        drawCircle(
            color = Color.White.copy(alpha = if (colors.isDark) 0.12f else 0.6f),
            radius = radius * 0.86f,
            center = center,
            style = Stroke(width = 1.2f),
        )
        if (running) {
            drawCircle(
                color = accent.copy(alpha = 0.25f * (1f - pulse)),
                radius = radius * (0.5f + 0.5f * pulse),
                center = center,
                style = Stroke(width = 2.5f),
            )
        }
        val live = radius * (0.42f + 0.5f * amplitude.coerceIn(0f, 1f))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.12f), Color.Transparent),
                center = center,
                radius = live,
            ),
            radius = live,
            center = center,
        )
        drawCircle(
            color = accent,
            radius = radius * 0.055f,
            center = center,
        )
        markers.forEach { position ->
            val angle = (-90f + position.coerceIn(0f, 1f) * 360f) * (Math.PI / 180f)
            val px = center.x + cos(angle).toFloat() * radius * 0.93f
            val py = center.y + sin(angle).toFloat() * radius * 0.93f
            drawCircle(color = Color.White, radius = 3.4f, center = Offset(px, py))
        }
    }
}

@Composable
fun SlideScreen(
    draft: TestDraft,
    phase: MeasurePhase,
    liveDeceleration: Double,
    slide: SlideResult?,
    sensorAvailable: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = PaceTheme.colors
    val running = phase is MeasurePhase.Running
    Column(Modifier.fillMaxSize()) {
        FlowHeader(step = "Step 3 of 3", title = "Friction test", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Lay the phone flat on the court, then push it firmly along the baseline. " +
                    "It slides, slows and stops; the deceleration is the friction.",
                style = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
                color = colors.textDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))

            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(22.dp),
            ) {
                Text(
                    text = "DECELERATION",
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                    ),
                    color = colors.textFaint,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.2f", liveDeceleration),
                        style = TextStyle(
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1.8).sp,
                        ),
                        color = colors.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "m/s²",
                        style = TextStyle(fontSize = 14.sp),
                        color = colors.textDim,
                        modifier = Modifier.padding(bottom = 9.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.55f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((liveDeceleration / 10.0).toFloat().coerceIn(0f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.accent),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = when {
                        slide != null -> "Coefficient of friction ${slide.cof.threeDecimal()}"
                        running -> phase.let { if (it is MeasurePhase.Running) it.hint else "Recording" }
                        else -> "Ready"
                    },
                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    color = if (slide != null) colors.accent else colors.textDim,
                )
            }

            if (phase is MeasurePhase.Failed) {
                Spacer(Modifier.height(14.dp))
                GlassNote(phase.message)
            }
            if (slide != null) {
                Spacer(Modifier.height(14.dp))
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(18.dp),
                ) {
                    StatRow("Mean deceleration", "${slide.meanDeceleration.twoDecimal()} m/s²")
                    HairLine()
                    StatRow("Slide duration", "${slide.durationSeconds.twoDecimal()} s")
                    HairLine()
                    StatRow("Coefficient of friction", slide.cof.threeDecimal(), emphasis = true)
                }
            }

            Spacer(Modifier.height(20.dp))
            if (!sensorAvailable) {
                GlassNote("No accelerometer on this device. Use the surface estimate instead.")
            } else if (running) {
                GlassButton(text = "Stop", onClick = onStop, prominent = false, modifier = Modifier.fillMaxWidth())
            } else {
                GlassButton(
                    text = if (slide == null) "Start slide test" else "Measure again",
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(10.dp))
            if (slide != null) {
                GlassButton(
                    text = "See the result",
                    onClick = onContinue,
                    prominent = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                GlassButton(
                    text = "Skip · use ${draft.surface.fallbackCof.threeDecimal()} for ${draft.surface.shortLabel}",
                    onClick = onSkip,
                    prominent = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            GlassNote(
                "Skipping is honest but weaker: the friction figure then comes from the surface " +
                    "family, not from this court. The result is labelled accordingly.",
            )
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
fun ResultScreen(
    pace: PaceResult,
    draft: TestDraft,
    bounce: BounceResult?,
    slide: SlideResult?,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = PaceTheme.colors
    Column(Modifier.fillMaxSize()) {
        FlowHeader(step = "Result", title = draft.name.ifBlank { draft.surface.label }, onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            PaceResultHeader(pace)
            Spacer(Modifier.height(22.dp))

            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                CoefficientBar(
                    label = "Coefficient of friction",
                    value = pace.cof,
                    min = 0.45,
                    max = 0.90,
                    bandLow = 0.56,
                    bandHigh = 0.71,
                    caption = if (pace.cofMeasured) {
                        "Measured by sliding this phone on this court."
                    } else {
                        "Estimated from the ${draft.surface.shortLabel.lowercase()} surface family - not measured here."
                    },
                )
                Spacer(Modifier.height(20.dp))
                CoefficientBar(
                    label = "Coefficient of restitution",
                    value = pace.corAdjusted,
                    min = 0.60,
                    max = 0.95,
                    bandLow = 0.79,
                    bandHigh = 0.85,
                    caption = "Temperature-corrected to 23 °C using the ITF 0.003 per °C coefficient.",
                )
                Spacer(Modifier.height(20.dp))
                ConfidenceMeter(pace.confidence)
            }

            Spacer(Modifier.height(16.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                SectionLabel("Measured")
                Spacer(Modifier.height(10.dp))
                StatRow("Court Pace Rating", pace.cpr.oneDecimal())
                HairLine()
                StatRow("Raw restitution", pace.cor.threeDecimal())
                HairLine()
                StatRow("Rebound height", "${pace.reboundHeightCm.roundInt()} cm")
                HairLine()
                StatRow("Drop height", "${draft.dropHeightCm.roundInt()} cm")
                HairLine()
                StatRow("Ball temperature", "${draft.temperatureC.oneDecimal()} °C")
                HairLine()
                StatRow("Impacts used", "${bounce?.impacts ?: 0}")
                HairLine()
                StatRow("Friction source", if (pace.cofMeasured) "Measured" else "Surface estimate")
            }

            Spacer(Modifier.height(16.dp))
            GlassNote(
                text = "Court Pace Rating = 100 × (1 − COF) + 150 × (0.81 − COR₂₃). " +
                    "Category bands: slow ≤ 29, medium-slow 30-34, medium 35-39, medium-fast 40-44, " +
                    "fast ≥ 45 (ITF CS 01/02).",
            )

            if (pace.cor < PaceEngine.MIN_RECOMMENDED_COR) {
                Spacer(Modifier.height(12.dp))
                GlassNote(
                    "Restitution below 0.70. The ITF does not recommend a surface this dead for tennis; " +
                        "check the surface and try a fresh ball.",
                )
            }

            Spacer(Modifier.height(22.dp))
            GlassButton(
                text = "Save this court",
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            GlassButton(
                text = "Discard",
                onClick = onDiscard,
                prominent = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
fun CourtDetailSheet(
    record: CourtRecord,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = PaceTheme.colors
    val accent = accentFor(record.category)
    Column(Modifier.fillMaxSize()) {
        FlowHeader(step = "Saved court", title = record.name, onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = record.category.displayName,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = accent,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = record.createdAt.asShortDate(),
                        style = TextStyle(fontSize = 11.5.sp),
                        color = colors.textFaint,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = record.cpi.toInt().toString(),
                        style = TextStyle(fontSize = 54.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
                        color = colors.text,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Court Pace Index",
                        style = TextStyle(fontSize = 12.sp),
                        color = colors.textFaint,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${record.surface.label} · ${if (record.indoor) "indoor" else "outdoor"}",
                    style = TextStyle(fontSize = 13.sp),
                    color = colors.textDim,
                )
                if (record.locationLabel.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = record.locationLabel,
                        style = TextStyle(fontSize = 12.5.sp),
                        color = colors.textFaint,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                StatRow("Court Pace Rating", record.cpr.oneDecimal())
                HairLine()
                StatRow("Coefficient of friction", record.cof.threeDecimal())
                HairLine()
                StatRow("Friction source", if (record.cofMeasured) "Measured" else "Surface estimate")
                HairLine()
                StatRow("Coefficient of restitution", record.cor.threeDecimal())
                HairLine()
                StatRow("Corrected restitution", record.corAdjusted.threeDecimal())
                HairLine()
                StatRow("Rebound height", "${record.reboundHeightCm.roundInt()} cm")
                HairLine()
                StatRow("Drop height", "${record.dropHeightCm.roundInt()} cm")
                HairLine()
                StatRow("Ball", record.ballType.label)
                HairLine()
                StatRow("Ball temperature", "${record.ballTemperatureC.oneDecimal()} °C")
                HairLine()
                StatRow("Impacts used", "${record.impacts}")
                HairLine()
                StatRow("Bounce spread", record.corSpread.threeDecimal())
                HairLine()
                StatRow("Confidence", "${(record.confidence * 100).toInt()}%")
            }

            if (record.notes.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                GlassNote(record.notes)
            }

            Spacer(Modifier.height(20.dp))
            GlassButton(
                text = "Delete this court",
                onClick = onDelete,
                prominent = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(120.dp))
        }
    }
}

internal fun Double.oneDecimal(): String = String.format(java.util.Locale.US, "%.1f", this)
internal fun Double.twoDecimal(): String = String.format(java.util.Locale.US, "%.2f", this)
internal fun Double.threeDecimal(): String = String.format(java.util.Locale.US, "%.3f", this)
internal fun Double.roundInt(): Int = Math.round(this).toInt()
