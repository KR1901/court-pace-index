package ink.courtpace.index.core.playerstats

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * India-specific date presentation for the Player Stats screen.
 *
 * Source timestamps are UTC (or carry an explicit UTC offset); date-only ranking
 * snapshots are calendar dates and must not be shifted across time zones.
 */
object PlayerStatsDateTime {
    private val indiaZone = ZoneId.of("Asia/Kolkata")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yy", Locale.US)
    private val dateTimeSecondsFormatter =
        DateTimeFormatter.ofPattern("dd-MM-yy HH:mm:ss", Locale.US)
    private val dateTimeMinutesFormatter =
        DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.US)

    /** Formats a date-only snapshot as DD-MM-YY, or converts an ISO timestamp to its IST date. */
    fun formatDate(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val dateOnly = runCatching {
            LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull()
        if (dateOnly != null) return dateOnly.format(dateFormatter)

        val instant = parseTimestamp(normalized) ?: return null
        return instant.atZone(indiaZone).format(dateFormatter)
    }

    /** Converts an ISO source timestamp to DD-MM-YY HH:mm[:ss] IST. */
    fun formatTimestamp(value: String?, includeSeconds: Boolean = true): String? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val instant = parseTimestamp(normalized) ?: return null
        val formatter = if (includeSeconds) dateTimeSecondsFormatter else dateTimeMinutesFormatter
        return instant.atZone(indiaZone).format(formatter) + " IST"
    }

    private fun parseTimestamp(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant() }
                .getOrNull()
            // Older archive timestamps without an explicit zone are defined by this feed as UTC.
            ?: runCatching {
                LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant(ZoneOffset.UTC)
            }.getOrNull()
}
