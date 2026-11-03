package ink.courtpace.index.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import ink.courtpace.index.core.physics.AccelSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

/**
 * Collects raw accelerometer readings (gravity included) for the slide test.
 */
class SlideMeter(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    val isAvailable: Boolean
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    fun samples(): Flow<AccelSample> = callbackFlow {
        val manager = sensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val start = System.nanoTime()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val t = (event.timestamp - start) / 1_000_000_000.0
                if (t < 0.0) return
                trySend(
                    AccelSample(
                        timeSeconds = t,
                        ax = event.values[0].toDouble(),
                        ay = event.values[1].toDouble(),
                        az = event.values[2].toDouble(),
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { manager.unregisterListener(listener) }
    }.flowOn(Dispatchers.Default)

    /**
     * Records for [durationMs] and returns everything captured. The window ends by
     * completing the flow rather than by throwing, so a normal end of window is never
     * mistaken for a failed measurement.
     */
    suspend fun capture(durationMs: Long, onLive: (Double) -> Unit): List<AccelSample> {
        val collected = ArrayList<AccelSample>(512)
        withTimeoutOrNull(durationMs + 2_000L) {
            samples()
                .takeWhile { it.timeSeconds * 1000.0 < durationMs }
                .collect { sample ->
                    collected.add(sample)
                    onLive(sqrt(sample.ax * sample.ax + sample.ay * sample.ay))
                }
        }
        return collected
    }
}
