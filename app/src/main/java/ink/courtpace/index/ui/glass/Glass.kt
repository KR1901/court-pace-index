package ink.courtpace.index.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.courtpace.index.ui.theme.PaceTheme

/** Slow-drifting mesh gradient that the glass surfaces sit on top of. */
@Composable
fun MeshBackground(
    modifier: Modifier = Modifier,
    accent: Color,
) {
    val colors = PaceTheme.colors
    val transition = rememberInfiniteTransition(label = "mesh")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(26_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )
    val driftB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(37_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftB",
    )

    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(colors.ink, colors.inkDeep)))
        blob(
            center = Offset(size.width * (0.05f + 0.20f * drift), size.height * 0.06f),
            radius = size.width * 1.05f,
            color = colors.blobA,
            strength = 0.75f,
        )
        blob(
            center = Offset(size.width * (0.95f - 0.18f * driftB), size.height * 0.42f),
            radius = size.width * 0.95f,
            color = colors.blobB,
            strength = 0.65f,
        )
        blob(
            center = Offset(size.width * (0.30f + 0.25f * driftB), size.height * 0.98f),
            radius = size.width * 1.1f,
            color = colors.blobC,
            strength = 0.7f,
        )
        blob(
            center = Offset(size.width * 0.82f, size.height * (0.10f + 0.05f * drift)),
            radius = size.width * 0.55f,
            color = accent,
            strength = if (colors.isDark) 0.30f else 0.22f,
        )
    }
}

private fun DrawScope.blob(center: Offset, radius: Float, color: Color, strength: Float) {
    val alpha = if (color.alpha > 0f) strength else 0f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.55f * alpha),
                color.copy(alpha = 0.22f * alpha),
                Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * The Liquid Glass material: a translucent tinted pane, a specular sheen along the top
 * edge, a bright rim that falls away to nothing at the bottom, and a soft inner shadow so
 * the pane reads as a physical layer rather than a flat rectangle.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(30.dp),
    tint: Color = Color.White,
    intensity: Float = 1f,
    elevation: Dp = 26.dp,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PaceTheme.colors
    val topAlpha = (if (colors.isDark) 0.16f else 0.62f) * intensity
    val midAlpha = (if (colors.isDark) 0.05f else 0.30f) * intensity
    val bottomAlpha = (if (colors.isDark) 0.09f else 0.42f) * intensity
    val fill = Brush.verticalGradient(
        listOf(
            tint.copy(alpha = topAlpha),
            tint.copy(alpha = midAlpha),
            tint.copy(alpha = bottomAlpha),
        ),
    )
    val rim = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = if (colors.isDark) 0.55f else 0.95f),
            Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.40f),
            Color.White.copy(alpha = if (colors.isDark) 0.04f else 0.20f),
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    Column(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (colors.isDark) 0.55f else 0.14f),
                spotColor = Color.Black.copy(alpha = if (colors.isDark) 0.65f else 0.18f),
            )
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, rim, shape)
            .drawBehind {
                // Specular sheen across the upper third.
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.45f),
                            Color.Transparent,
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width * 0.75f, size.height * 0.9f),
                    ),
                )
                // Inner shadow along the bottom edge.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = if (colors.isDark) 0.22f else 0.05f),
                        ),
                        startY = size.height * 0.55f,
                        endY = size.height,
                    ),
                )
            }
            .padding(contentPadding),
        content = content,
    )
}

/** Pill-shaped glass button. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = true,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = PaceTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessHigh),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current
    val background = if (prominent) {
        Brush.linearGradient(
            listOf(
                colors.accent.copy(alpha = if (colors.isDark) 0.92f else 1f),
                colors.accent.copy(alpha = if (colors.isDark) 0.70f else 0.85f),
            ),
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (colors.isDark) 0.16f else 0.75f),
                Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.45f),
            ),
        )
    }
    val label = if (prominent) {
        if (colors.isDark) Color(0xFF06080D) else Color.White
    } else {
        colors.text
    }

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .shadow(
                elevation = if (prominent) 18.dp else 8.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = colors.accent.copy(alpha = 0.4f),
                spotColor = colors.accent.copy(alpha = 0.5f),
            )
            .clip(CircleShape)
            .background(background, CircleShape)
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.05f)),
                ),
                CircleShape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 24.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = text,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.1.sp,
            ),
            color = label,
        )
    }
}

/** Selectable glass chip. */
@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessHigh),
        label = "chipPress",
    )
    val fill by animateColorAsState(
        targetValue = if (selected) {
            colors.accent.copy(alpha = if (colors.isDark) 0.26f else 0.30f)
        } else {
            Color.White.copy(alpha = if (colors.isDark) 0.07f else 0.55f)
        },
        animationSpec = tween(260),
        label = "chipFill",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f),
        animationSpec = tween(260),
        label = "chipBorder",
    )
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton) {
                onClick()
            }
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.1.sp,
            ),
            color = if (selected) colors.text else colors.textDim,
        )
    }
}

/** Small uppercase section label used above groups. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val colors = PaceTheme.colors
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = TextStyle(
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
        ),
        color = colors.textFaint,
    )
}

/** Key/value stat row inside a glass card. */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    emphasis: Boolean = false,
) {
    val colors = PaceTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = if (emphasis) 14.sp else 13.5.sp),
            color = colors.textDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = TextStyle(
                fontSize = if (emphasis) 15.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.1).sp,
            ),
            color = valueColor ?: colors.text,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

enum class Glyph { HOME, GAUGE, LIBRARY, METHOD, SETTINGS }

/** Bespoke stroke glyphs, drawn rather than pulled from an icon font. */
@Composable
fun NavGlyph(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.HOME -> {
                val roof = Path().apply {
                    moveTo(w * 0.12f, h * 0.46f)
                    lineTo(w * 0.5f, h * 0.13f)
                    lineTo(w * 0.88f, h * 0.46f)
                }
                drawPath(roof, color, style = stroke)
                val body = Path().apply {
                    moveTo(w * 0.22f, h * 0.44f)
                    lineTo(w * 0.22f, h * 0.86f)
                    lineTo(w * 0.78f, h * 0.86f)
                    lineTo(w * 0.78f, h * 0.44f)
                }
                drawPath(body, color, style = stroke)
            }

            Glyph.GAUGE -> {
                drawArc(
                    color = color,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = Offset(w * 0.10f, h * 0.18f),
                    size = Size(w * 0.80f, h * 0.80f),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.56f),
                    end = Offset(w * 0.72f, h * 0.34f),
                    strokeWidth = w * 0.085f,
                    cap = StrokeCap.Round,
                )
            }

            Glyph.LIBRARY -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.18f),
                    size = Size(w * 0.76f, h * 0.20f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.07f),
                    style = stroke,
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.45f),
                    size = Size(w * 0.76f, h * 0.16f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.07f),
                    style = stroke,
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.68f),
                    size = Size(w * 0.48f, h * 0.16f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.07f),
                    style = stroke,
                )
            }

            Glyph.METHOD -> {
                drawCircle(
                    color = color,
                    radius = w * 0.38f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.30f),
                    end = Offset(w * 0.5f, h * 0.55f),
                    strokeWidth = w * 0.085f,
                    cap = StrokeCap.Round,
                )
                drawCircle(color = color, radius = w * 0.05f, center = Offset(w * 0.5f, h * 0.68f))
            }

            Glyph.SETTINGS -> {
                drawCircle(
                    color = color,
                    radius = w * 0.20f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = stroke,
                )
                for (i in 0 until 6) {
                    val angle = Math.toRadians((i * 60).toDouble())
                    val cx = w * 0.5f + Math.cos(angle).toFloat() * w * 0.36f
                    val cy = h * 0.5f + Math.sin(angle).toFloat() * h * 0.36f
                    drawCircle(color = color, radius = w * 0.055f, center = Offset(cx, cy))
                }
            }
        }
    }
}

/** Floating glass tab bar. */
@Composable
fun GlassTabBar(
    items: List<Pair<Glyph, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        intensity = 1.15f,
        elevation = 30.dp,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { index, (glyph, label) ->
                TabItem(
                    glyph = glyph,
                    label = label,
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    glyph: Glyph,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PaceTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        targetValue = if (selected) colors.accent.copy(alpha = if (colors.isDark) 0.22f else 0.26f)
        else Color.Transparent,
        animationSpec = tween(280),
        label = "tabTint",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.textFaint,
        animationSpec = tween(280),
        label = "tabIcon",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "tabScale",
    )
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(tint, RoundedCornerShape(24.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab) {
                onClick()
            }
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavGlyph(glyph = glyph, color = iconColor)
        Text(
            text = label,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.2.sp,
            ),
            color = if (selected) colors.text else colors.textFaint,
        )
    }
}

/** Empty-state / inline note block. */
@Composable
fun GlassNote(text: String, modifier: Modifier = Modifier) {
    val colors = PaceTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = if (colors.isDark) 0.05f else 0.5f))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(3.dp)
                .height(14.dp)
                .background(colors.accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = colors.textDim,
        )
    }
}

@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), content = content)
}
