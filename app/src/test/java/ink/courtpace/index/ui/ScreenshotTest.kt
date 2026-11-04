package ink.courtpace.index.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

    @Test
    fun boardLight() = shoot("board__light") { Frame(dark = false) { board()() } }

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

    @Test
    fun method() = shoot("method__dark") { Frame { MethodScreen(data = data) } }
}
