package ink.courtpace.index.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ink.courtpace.index.core.model.PaceCategory
import ink.courtpace.index.data.ThemeMode

/**
 * Liquid Glass palette: deep ink glass in dark mode, milky glass in light mode, with the
 * pace category as the only saturated colour on screen.
 */
@Immutable
data class PaceColors(
    val isDark: Boolean,
    val ink: Color,
    val inkDeep: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
    val glassTint: Color,
    val glassHighlight: Color,
    val danger: Color,
)

fun accentFor(category: PaceCategory): Color = when (category) {
    PaceCategory.SLOW -> Color(0xFFE07A48)
    PaceCategory.MEDIUM_SLOW -> Color(0xFFD8A03C)
    PaceCategory.MEDIUM -> Color(0xFF4FB07C)
    PaceCategory.MEDIUM_FAST -> Color(0xFF3E92D8)
    PaceCategory.FAST -> Color(0xFF8B7BFF)
}

val DefaultAccent = Color(0xFF7FE0C0)

fun paceColors(dark: Boolean, accent: Color): PaceColors = if (dark) {
    PaceColors(
        isDark = true,
        ink = Color(0xFF05060A),
        inkDeep = Color(0xFF010204),
        text = Color(0xFFF4F6FB),
        textDim = Color(0xFFA8B0C2),
        textFaint = Color(0xFF6E7689),
        accent = accent,
        accentSoft = accent.copy(alpha = 0.18f),
        blobA = Color(0xFF1E2F6B),
        blobB = Color(0xFF43206E),
        blobC = Color(0xFF0B4A48),
        glassTint = Color.White,
        glassHighlight = Color.White,
        danger = Color(0xFFFF7A7A),
    )
} else {
    PaceColors(
        isDark = false,
        ink = Color(0xFFF1F3F9),
        inkDeep = Color(0xFFE4E8F2),
        text = Color(0xFF0B0E17),
        textDim = Color(0xFF4C5567),
        textFaint = Color(0xFF8A93A5),
        accent = accent,
        accentSoft = accent.copy(alpha = 0.16f),
        blobA = Color(0xFFBFD3FF),
        blobB = Color(0xFFE3C8FF),
        blobC = Color(0xFFBFF0E4),
        glassTint = Color.White,
        glassHighlight = Color.White,
        danger = Color(0xFFC63B3B),
    )
}

val LocalPaceColors = staticCompositionLocalOf { paceColors(dark = true, accent = DefaultAccent) }

object PaceTheme {
    val colors: PaceColors
        @Composable get() = LocalPaceColors.current
}

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 60.sp, lineHeight = 62.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-2.2).sp,
    ),
    displayMedium = TextStyle(
        fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-1.6).sp,
    ),
    displaySmall = TextStyle(
        fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleSmall = TextStyle(
        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
        letterSpacing = (-0.1).sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 1.1.sp,
    ),
)

@Composable
fun CourtPaceTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accent: Color = DefaultAccent,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = paceColors(dark, accent)
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            background = colors.ink,
            surface = colors.ink,
            onBackground = colors.text,
            onSurface = colors.text,
            onSurfaceVariant = colors.textDim,
        )
    } else {
        lightColorScheme(
            primary = accent,
            background = colors.ink,
            surface = colors.ink,
            onBackground = colors.text,
            onSurface = colors.text,
            onSurfaceVariant = colors.textDim,
        )
    }
    CompositionLocalProvider(LocalPaceColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
