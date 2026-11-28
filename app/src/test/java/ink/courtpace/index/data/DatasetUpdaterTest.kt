package ink.courtpace.index.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DatasetUpdaterTest {

    @Test
    fun boundedReaderAcceptsPayloadExactlyAtLimit() {
        val payload = "court-pace".toByteArray(Charsets.UTF_8)

        val result = readBoundedDatasetText(
            ByteArrayInputStream(payload),
            payload.size,
        )

        assertEquals("court-pace", result)
    }

    @Test
    fun boundedReaderRejectsPayloadLargerThanLimit() {
        val payload = "oversized".toByteArray(Charsets.UTF_8)

        try {
            readBoundedDatasetText(ByteArrayInputStream(payload), payload.size - 1)
            throw AssertionError("Expected an oversized payload to be rejected")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("size limit"))
        }
    }


    @Test
    fun cachedRestoresBackupAfterAnInterruptedAtomicWrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = File(context.filesDir, "dataset_cache.json")
        val backup = File(context.filesDir, "dataset_cache.json.bak")
        cache.delete()
        backup.writeText(
            """{"fetchedAt":123,"dataset":{"meta":{"generated":"2026-11-28T00:00:00Z","season":2026},"tournaments":[],"readings":[]}}""",
        )

        val restored = DatasetUpdater(context).cached()

        assertEquals(123L, restored?.fetchedAt)
        assertTrue(cache.exists())
    }

    @Test
    fun boundedReaderPreservesUtf8WhenLimitIsMeasuredInBytes() {
        val payload = "é".toByteArray(Charsets.UTF_8)

        val result = readBoundedDatasetText(ByteArrayInputStream(payload), 2)

        assertEquals("é", result)
    }
}
