package ink.courtpace.index

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ink.courtpace.index.core.playerstats.PlayerStatsLiveUpdater
import ink.courtpace.index.core.playerstats.PlayerStatsRefreshScheduler
import ink.courtpace.index.ui.CourtPaceRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerStatsRefreshScheduler.schedule(this)
        // Keep Player Stats current while any app tab is in the foreground, not just its own tab.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                        PlayerStatsLiveUpdater.refresh(applicationContext)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        PlayerStatsLiveUpdater.reportFailure(
                            failure.message ?: "Automatic Player Stats sync failed.",
                        )
                    }
                    delay(60_000L)
                }
            }
        }
        // Transparent bars in both themes: the app draws behind them, and the icon
        // appearance is driven from the app's own theme state in CourtPaceRoot, so the
        // system bar content can never disagree with what is on screen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            CourtPaceRoot()
        }
    }
}
