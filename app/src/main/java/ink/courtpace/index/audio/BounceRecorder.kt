package ink.courtpace.index.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Records the bounce test. Audio is held in memory, analysed, and dropped — nothing is
 * written to storage or leaves the device.
 */
class BounceRecorder {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(
        durationMs: Long,
        onAmplitude: (Float) -> Unit,
    ): ShortArray = withContext(Dispatchers.IO) {
        val sampleRate = SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, sampleRate / 4 * 2)

        val audioRecord = createRecorder(sampleRate, bufferSize)
        if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            return@withContext ShortArray(0)
        }

        val totalSamples = (sampleRate * durationMs / 1000L).toInt().coerceAtLeast(sampleRate / 4)
        val out = ShortArray(totalSamples)
        val chunk = ShortArray(bufferSize / 2)
        var written = 0

        try {
            audioRecord.startRecording()
            while (written < totalSamples && coroutineContext.isActive) {
                val read = audioRecord.read(chunk, 0, minOf(chunk.size, totalSamples - written))
                if (read <= 0) break
                chunk.copyInto(out, written, 0, read)
                written += read
                var peak = 0
                for (i in 0 until read) {
                    val v = abs(chunk[i].toInt())
                    if (v > peak) peak = v
                }
                onAmplitude((peak / 32768f).coerceIn(0f, 1f))
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
        if (written == out.size) out else out.copyOf(written)
    }

    private fun createRecorder(sampleRate: Int, bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val record = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.getOrNull()
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) return record
            record?.release()
        }
        return null
    }

    companion object {
        const val SAMPLE_RATE = 44_100
    }
}
