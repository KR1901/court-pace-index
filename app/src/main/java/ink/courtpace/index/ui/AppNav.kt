package ink.courtpace.index.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ink.courtpace.index.ui.glass.GlassTabBar
import ink.courtpace.index.ui.glass.Glyph
import ink.courtpace.index.ui.glass.MeshBackground
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

    CourtPaceTheme(accent = accent) {
        val colors = PaceTheme.colors
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

        Box(Modifier.fillMaxSize().background(colors.ink)) {
            MeshBackground(accent = accent)

            val data = dataState
            if (data == null) {
                LoadingState(message = error)
            } else {
                AnimatedContent(
                    targetState = route,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically { it / 30 }) togetherWith
                            (fadeOut(tween(140)) + slideOutVertically { -it / 60 })
                    },
                    modifier = Modifier.fillMaxSize(),
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
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

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
