package ink.courtpace.index.data

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun boundedReaderPreservesUtf8WhenLimitIsMeasuredInBytes() {
        val payload = "é".toByteArray(Charsets.UTF_8)

        val result = readBoundedDatasetText(ByteArrayInputStream(payload), 2)

        assertEquals("é", result)
    }
}
