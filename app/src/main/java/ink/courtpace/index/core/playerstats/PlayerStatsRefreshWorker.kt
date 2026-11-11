package ink.courtpace.index.core.playerstats

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Runs independently of the Player Stats screen so the local cache refreshes in the background. */
class PlayerStatsRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        PlayerStatsLiveUpdater.refresh(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

object PlayerStatsRefreshScheduler {
    private const val PERIODIC_NAME = "court-pace-player-stats-periodic"
    private const val INITIAL_NAME = "court-pace-player-stats-initial"

    /**
     * Schedule an immediate one-time sync plus periodic background checks.
     * WorkManager's 15-minute minimum interval is intentionally used rather than an alarm.
     */
    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<PlayerStatsRefreshWorker>(
            15, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val manager = WorkManager.getInstance(appContext)
        manager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )

        val initial = OneTimeWorkRequestBuilder<PlayerStatsRefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork(INITIAL_NAME, ExistingWorkPolicy.KEEP, initial)
    }
}
