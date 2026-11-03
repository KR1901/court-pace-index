package ink.courtpace.index.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ink.courtpace.index.core.model.CourtRecord
import ink.courtpace.index.ui.glass.GlassTabBar
import ink.courtpace.index.ui.glass.Glyph
import ink.courtpace.index.ui.glass.MeshBackground
import ink.courtpace.index.ui.screens.BounceScreen
import ink.courtpace.index.ui.screens.CourtDetailSheet
import ink.courtpace.index.ui.screens.CourtsScreen
import ink.courtpace.index.ui.screens.HomeScreen
import ink.courtpace.index.ui.screens.MethodScreen
import ink.courtpace.index.ui.screens.ResultScreen
import ink.courtpace.index.ui.screens.SettingsScreen
import ink.courtpace.index.ui.screens.SetupScreen
import ink.courtpace.index.ui.screens.SlideScreen
import ink.courtpace.index.ui.theme.CourtPaceTheme
import ink.courtpace.index.ui.theme.DefaultAccent
import ink.courtpace.index.ui.theme.PaceTheme
import ink.courtpace.index.ui.theme.accentFor

sealed interface Route {
    data class Tab(val index: Int) : Route
    data object Setup : Route
    data object Bounce : Route
    data object Slide : Route
    data object Result : Route
    data class Detail(val record: CourtRecord) : Route
}

private val tabs = listOf(
    Glyph.HOME to "Home",
    Glyph.LIBRARY to "Courts",
    Glyph.METHOD to "Method",
    Glyph.SETTINGS to "Settings",
)

@Composable
fun CourtPaceRoot(vm: AppViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val courts by vm.courts.collectAsStateWithLifecycle()
    val pace = vm.result()
    val accent = pace?.let { accentFor(it.category) } ?: DefaultAccent

    CourtPaceTheme(themeMode = settings.theme, accent = accent) {
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

        fun goTab(index: Int) {
            stack = emptyList()
            route = Route.Tab(index)
        }

        BackHandler(enabled = stack.isNotEmpty()) { pop() }

        Box(Modifier.fillMaxSize().background(colors.ink)) {
            MeshBackground(accent = accent)

            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInVertically { it / 26 }) togetherWith
                        (fadeOut(tween(150)) + slideOutVertically { -it / 60 })
                },
                modifier = Modifier.fillMaxSize(),
                label = "route",
            ) { current ->
                when (current) {
                    is Route.Tab -> when (current.index) {
                        0 -> HomeScreen(
                            courts = courts,
                            latest = courts.firstOrNull(),
                            onStartTest = {
                                vm.startNewTest()
                                push(Route.Setup)
                            },
                            onOpenCourt = { push(Route.Detail(it)) },
                            onOpenMethod = { goTab(2) },
                        )

                        1 -> CourtsScreen(
                            courts = courts,
                            onOpen = { push(Route.Detail(it)) },
                            onStartTest = {
                                vm.startNewTest()
                                push(Route.Setup)
                            },
                        )

                        2 -> MethodScreen()
                        else -> SettingsScreen(
                            settings = settings,
                            courtCount = courts.size,
                            onUpdate = { transform -> vm.updateSettings(transform) },
                            onExport = { vm.exportPayload() },
                            onImport = { vm.importPayload(it) },
                        )
                    }

                    Route.Setup -> SetupScreen(
                        draft = vm.draft,
                        onDraftChange = { transform -> vm.updateDraft(transform) },
                        onBack = { pop() },
                        onContinue = { push(Route.Bounce) },
                    )

                    Route.Bounce -> BounceScreen(
                        draft = vm.draft,
                        phase = vm.phase,
                        amplitude = vm.amplitude,
                        bounce = vm.bounce,
                        impactMarkers = vm.impactMarkers,
                        onStart = { vm.runBounce() },
                        onStop = { vm.stopBounce() },
                        onContinue = { push(Route.Slide) },
                        onBack = {
                            vm.stopBounce()
                            pop()
                        },
                    )

                    Route.Slide -> SlideScreen(
                        draft = vm.draft,
                        phase = vm.phase,
                        liveDeceleration = vm.liveDeceleration,
                        slide = vm.slide,
                        sensorAvailable = vm.slideSensorAvailable,
                        onStart = { vm.runSlide() },
                        onStop = { vm.stopSlide() },
                        onSkip = {
                            vm.useEstimatedFriction()
                            push(Route.Result)
                        },
                        onContinue = { push(Route.Result) },
                        onBack = {
                            vm.stopSlide()
                            pop()
                        },
                    )

                    Route.Result -> {
                        val currentPace = pace
                        if (currentPace == null) {
                            BounceScreen(
                                draft = vm.draft,
                                phase = vm.phase,
                                amplitude = vm.amplitude,
                                bounce = vm.bounce,
                                impactMarkers = vm.impactMarkers,
                                onStart = { vm.runBounce() },
                                onStop = { vm.stopBounce() },
                                onContinue = {},
                                onBack = { pop() },
                            )
                        } else {
                            ResultScreen(
                                pace = currentPace,
                                draft = vm.draft,
                                bounce = vm.bounce,
                                slide = vm.slide,
                                onSave = {
                                    vm.saveCourt { goTab(0) }
                                },
                                onDiscard = {
                                    vm.startNewTest()
                                    goTab(0)
                                },
                                onBack = { pop() },
                            )
                        }
                    }

                    is Route.Detail -> CourtDetailSheet(
                        record = current.record,
                        onDelete = {
                            vm.deleteCourt(current.record.id)
                            pop()
                        },
                        onBack = { pop() },
                    )
                }
            }

            if (route is Route.Tab) {
                GlassTabBar(
                    items = tabs,
                    selected = (route as Route.Tab).index,
                    onSelect = { goTab(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
