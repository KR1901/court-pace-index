package ink.courtpace.index.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.courtpace.index.core.Datasets
import ink.courtpace.index.core.data.PaceData
import ink.courtpace.index.core.model.Metric
import ink.courtpace.index.ui.glass.MeshBackground
import ink.courtpace.index.ui.screens.BoardScreen
import ink.courtpace.index.ui.screens.CompareScreen
import ink.courtpace.index.ui.screens.MethodScreen
import ink.courtpace.index.ui.screens.SurfacesScreen
import ink.courtpace.index.ui.screens.TournamentScreen
import ink.courtpace.index.ui.theme.CourtPaceTheme
import ink.courtpace.index.ui.theme.DefaultAccent
import ink.courtpace.index.ui.theme.PaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every screen to a PNG so the UI can be looked at without an emulator.
 *
 * Robolectric with native graphics draws the real Compose tree; the files land in
 * build/screenshots and are the input to the design audit.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val data = PaceData.parse(Datasets.raw())

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { content() }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("shots.dir") ?: "/scratch/work/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("captured $name ${bitmap.width}x${bitmap.height}")
    }

    @Composable
    private fun Frame(dark: Boolean = true, content: @Composable () -> Unit) {
        CourtPaceTheme(dark = dark, accent = DefaultAccent) {
            val colors = PaceTheme.colors
            Box(Modifier.fillMaxSize().background(colors.ink)) {
                MeshBackground(accent = DefaultAccent)
                content()
            }
        }
    }

    private fun board() = @Composable {
        BoardScreen(
            data = data,
            season = data.latestSeason,
            metric = Metric.CPI,
            surfaces = emptySet(),
            seasons = data.seasons.filter { data.entries(it, Metric.CPI).isNotEmpty() },
            onSeason = {}, onMetric = {}, onSurface = {}, onOpen = {},
            refreshing = false, onRefresh = {}, statusNote = null,
        )
    }

    @Test
    fun boardDark() = shoot("board__dark") { Frame { board()() } }

    /**
     * Mid-scroll: content must be visible *under* the status bar, which is what edge-to-edge
     * means and what the previous inset placement made impossible.
     */
    @Test
    fun boardScrolled() = shoot("board__scrolled") {
        Frame {
            val scroll = androidx.compose.foundation.rememberScrollState(initial = 520)
            BoardScreen(
                data = data,
                season = data.latestSeason,
                metric = Metric.CPI,
                surfaces = emptySet(),
                seasons = data.seasons.filter { data.entries(it, Metric.CPI).isNotEmpty() },
                onSeason = {}, onMetric = {}, onSurface = {}, onOpen = {},
                refreshing = false, onRefresh = {}, statusNote = null,
                scrollState = scroll,
            )
        }
    }

    @Test
    fun boardLight() = shoot("board__light") { Frame(dark = false) { board()() } }

    /** The reported bug: system in night mode, app came up light. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun systemNightSelectsDarkTheme() {
        var sys = false
        var viaExplicit: Boolean? = null
        compose.setContent {
            sys = androidx.compose.foundation.isSystemInDarkTheme()
            CourtPaceTheme(dark = sys, accent = DefaultAccent) {
                viaExplicit = PaceTheme.colors.isDark
            }
        }
        compose.waitForIdle()
        println("DIAG sys=$sys explicit=$viaExplicit")
        assert(viaExplicit == true) { "explicit dark must give the dark palette, got $viaExplicit" }
    }

    @Test
    fun tournament() = shoot("tournament__dark") {
        Frame {
            TournamentScreen(
                data = data,
                tournament = data.tournament("madrid")!!,
                season = data.latestSeason,
                onBack = {},
            )
        }
    }

    @Test
    fun compare() = shoot("compare__dark") {
        Frame {
            CompareScreen(
                data = data,
                season = data.latestSeason,
                selected = listOf("miami", "cincinnati", "montecarlo"),
                onToggle = {}, onOpen = {},
            )
        }
    }

    @Test
    fun surfaces() = shoot("surfaces__dark") {
        Frame { SurfacesScreen(data = data, season = data.latestSeason, metric = Metric.CPI) }
    }

    /** Landscape: the configuration where an unhandled side inset is obvious. */
    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun boardLandscape() = shoot("board__landscape") { Frame { board()() } }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun boardLargeText() = shoot("board__font2") { Frame { board()() } }

    @Test
    fun method() = shoot("method__dark") { Frame { MethodScreen(data = data) } }

    /** The bar over real rows, which is where it was reported as obstructive. */
    @Test
    fun boardWithTabBar() = shoot("board__tabbar") {
        Frame {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                board()()
                ink.courtpace.index.ui.glass.GlassTabBar(
                    items = listOf(
                        ink.courtpace.index.ui.glass.Glyph.GAUGE to "Pace",
                        ink.courtpace.index.ui.glass.Glyph.LIBRARY to "Compare",
                        ink.courtpace.index.ui.glass.Glyph.HOME to "Surfaces",
                        ink.courtpace.index.ui.glass.Glyph.METHOD to "Method",
                    ),
                    selected = 0,
                    onSelect = {},
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
