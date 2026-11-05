package ink.courtpace.index.ui

import android.app.Activity
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ink.courtpace.index.ui.glass.GlassTabBar
import ink.courtpace.index.ui.glass.Glyph
import ink.courtpace.index.ui.glass.LocalTabBarInset
import ink.courtpace.index.ui.glass.MeshBackground
import ink.courtpace.index.ui.glass.TabBarBehaviour
import ink.courtpace.index.ui.glass.rememberReduceMotion
import ink.courtpace.index.ui.screens.BoardScreen
import ink.courtpace.index.ui.screens.CompareScreen
import ink.courtpace.index.ui.screens.MethodScreen
import ink.courtpace.index.ui.screens.SurfacesScreen
import ink.courtpace.index.ui.screens.TournamentScreen
import ink.courtpace.index.ui.theme.CourtPaceTheme
import ink.courtpace.index.ui.theme.DefaultAccent
import ink.courtpace.index.ui.theme.PaceTheme

sealed interface Route {
    data class Tab(val index: Int) : Route
    data class Detail(val tournamentId: String) : Route
}

private val tabs = listOf(
    Glyph.GAUGE to "Pace",
    Glyph.LIBRARY to "Compare",
    Glyph.HOME to "Surfaces",
    Glyph.METHOD to "Method",
)

@Composable
fun CourtPaceRoot(vm: AppViewModel = viewModel()) {
    val dataState by vm.data.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = DefaultAccent

    val dark = isSystemInDarkTheme()
    CourtPaceTheme(dark = dark, accent = accent) {
        val colors = PaceTheme.colors
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        var route by remember { mutableStateOf<Route>(Route.Tab(0)) }
        var stack by remember { mutableStateOf(listOf<Route>()) }

        fun push(next: Route) {
            stack = stack + route
            route = next
        }

        fun pop(): Boolean {
            val previous = stack.lastOrNull() ?: return false
            stack = stack.dropLast(1)
            route = previous
            return true
        }

        BackHandler(enabled = stack.isNotEmpty()) { pop() }

        // The refresh result is a transient note, not a permanent banner.
        val refreshState = vm.refreshState
        LaunchedEffect(refreshState) {
            if (refreshState is RefreshState.Done || refreshState is RefreshState.Failed) {
                kotlinx.coroutines.delay(6_000)
                vm.clearRefreshState()
            }
        }

        val context = LocalContext.current
        val reduceMotion = rememberReduceMotion()
        val touchExploration = remember(context) { context.isTouchExplorationActive() }

        // The bar steps out of the way on the way down and comes back on the way up: the
        // behaviour both Apple's and Google's current guidance describe for a bar that floats
        // over scrolling content. It never hides while a screen reader is exploring, because
        // then it is navigation the user cannot afford to lose.
        var barHidden by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        val threshold = with(density) { 12.dp.toPx() }
        val connection = remember(touchExploration, threshold) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    TabBarBehaviour.next(available.y, threshold, touchExploration)
                        ?.let { barHidden = it }
                    return Offset.Zero
                }
            }
        }

        LaunchedEffect(route) { barHidden = false }

        var barHeight by remember { mutableStateOf(76.dp) }
        val navInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val hideOffset by animateDpAsState(
            targetValue = if (barHidden) barHeight + 28.dp else 0.dp,
            animationSpec = if (reduceMotion) snap() else tween(220),
            label = "barSlide",
        )

        Box(Modifier.fillMaxSize().background(colors.ink)) {
            MeshBackground(accent = accent)

            val data = dataState
            if (data == null) {
                LoadingState(message = error)
            } else {
                CompositionLocalProvider(
                    LocalTabBarInset provides (barHeight + 20.dp + navInset),
                ) {
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically { it / 30 }) togetherWith
                                (fadeOut(tween(140)) + slideOutVertically { -it / 60 })
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(connection)
                            // shortEdges lets content run into a display cutout; in landscape
                            // that is the camera, so inset horizontally and nowhere else.
                            .windowInsetsPadding(
                                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                            ),
                        label = "route",
                    ) { current ->
                        when (current) {
                            is Route.Tab -> when (current.index) {
                                0 -> BoardScreen(
                                    data = data,
                                    season = vm.season,
                                    metric = vm.metric,
                                    surfaces = vm.surfaces,
                                    seasons = vm.seasonsFor(data, vm.metric),
                                    onSeason = { vm.selectSeason(it) },
                                    onMetric = { vm.selectMetric(it) },
                                    onSurface = { vm.toggleSurface(it) },
                                    onOpen = { push(Route.Detail(it.id)) },
                                    refreshing = vm.refreshState is RefreshState.Running,
                                    onRefresh = { vm.refresh() },
                                    livePace = vm.livePace,
                                    liveAce = vm.liveAce,
                                    liveProgress = vm.liveAceProgress,
                                    liveNote = vm.livePaceNote,
                                    statusNote = when (val state = vm.refreshState) {
                                        is RefreshState.Done -> state.message
                                        is RefreshState.Failed -> state.message
                                        else -> null
                                    },
                                )

                                1 -> CompareScreen(
                                    data = data,
                                    season = vm.season,
                                    selected = vm.compareIds,
                                    onToggle = { vm.toggleCompare(it) },
                                    onOpen = { push(Route.Detail(it.id)) },
                                )

                                2 -> SurfacesScreen(
                                    data = data,
                                    season = vm.season,
                                    metric = vm.metric,
                                )

                                else -> MethodScreen(data = data)
                            }

                            is Route.Detail -> {
                                val tournament = data.tournament(current.tournamentId)
                                if (tournament == null) {
                                    pop()
                                } else {
                                    TournamentScreen(
                                        data = data,
                                        tournament = tournament,
                                        season = vm.season,
                                        onBack = { pop() },
                                    )
                                }
                            }
                        }
                    }

                    if (route is Route.Tab) {
                        GlassTabBar(
                            items = tabs,
                            selected = (route as Route.Tab).index,
                            onSelect = { index ->
                                stack = emptyList()
                                route = Route.Tab(index)
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(
                                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                                    ),
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .onGloballyPositioned { coordinates ->
                                    barHeight = with(density) { coordinates.size.height.toDp() }
                                }
                                .offset(y = hideOffset),
                        )
                    }
                }
            }
        }
    }
}

private fun Context.isTouchExplorationActive(): Boolean =
    (getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
        ?.isTouchExplorationEnabled == true

@Composable
private fun LoadingState(message: String?) {
    val colors = PaceTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message ?: "Reading the dataset…",
            color = colors.textDim,
            modifier = Modifier.padding(32.dp),
            style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            textAlign = TextAlign.Center,
        )
    }
}
