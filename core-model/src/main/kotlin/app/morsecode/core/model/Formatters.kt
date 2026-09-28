package app.morsecode.core.model

import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale

/**
 * Localised unit labels and relative-time phrases.
 *
 * Formatting logic lives in [MorseFormatters] (pure JVM, unit testable) while the
 * words come from here. On Android the implementation is backed by string and
 * plural resources, so nothing is concatenated from English fragments (§11).
 */
public interface UnitLabels {
    public val gb: String
    public val mb: String
    public val kb: String
    public val mbPerSecond: String
    public val percentSign: String
    public val today: String
    public val yesterday: String
    public fun justNow(): String
    public fun minutesAgo(minutes: Long): String
    public fun hoursAgo(hours: Long): String
    public fun daysAgo(days: Long): String

    public companion object {
        /** English fallback used by JVM modules, tests and the WebShare API. */
        public val ENGLISH: UnitLabels = object : UnitLabels {
            override val gb: String = " GB"
            override val mb: String = " MB"
            override val kb: String = " KB"
            override val mbPerSecond: String = " MB/s"
            override val percentSign: String = "%"
            override val today: String = "Today"
            override val yesterday: String = "Yesterday"
            override fun justNow(): String = "just now"
            override fun minutesAgo(minutes: Long): String = "$minutes min ago"
            override fun hoursAgo(hours: Long): String = "$hours h ago"
            override fun daysAgo(days: Long): String = if (days == 1L) "1 day ago" else "$days days ago"
        }
    }
}

/**
 * Locale aware formatting shared by the phone UI, the WebShare JSON API and the
 * tests.
 *
 * Numeric thresholds reproduce the mockup's own `fmtMB` / `fmtSpd` / `fmtTime`
 * helpers so a 144 MB file reads identically in both references, while the
 * decimal separator and grouping follow [locale].
 */
public class MorseFormatters(
    private val locale: Locale,
    private val units: UnitLabels = UnitLabels.ENGLISH,
) {

    private val oneDecimal = DecimalFormat("0.#", DecimalFormatSymbols(locale))
    private val noDecimal = DecimalFormat("0", DecimalFormatSymbols(locale))
    private val speedDecimal = DecimalFormat("0.0", DecimalFormatSymbols(locale))

    /**
     * Bytes to a compact label: GB with one decimal, MB with one decimal below
     * 10 MB and rounded above it, otherwise KB with one decimal.
     */
    public fun bytes(value: Long): String {
        val safe = if (value < 0L) 0L else value
        return when {
            safe >= 1_000_000_000L -> "${oneDecimal.format(safe / 1_000_000_000.0)}${units.gb}"
            safe >= 1_000_000L -> {
                val mb = safe / 1_000_000.0
                if (mb < 10.0) "${oneDecimal.format(mb)}${units.mb}" else "${noDecimal.format(mb)}${units.mb}"
            }
            else -> "${oneDecimal.format(safe / 1_000.0)}${units.kb}"
        }
    }

    /** "48.9 MB / 144 MB" style progress label for a transfer row. */
    public fun progress(transferred: Long, total: Long): String = "${bytes(transferred)} / ${bytes(total)}"

    /** Throughput, always one decimal: the mockup prints "6.2 MB/s". */
    public fun speed(bytesPerSecond: Long): String =
        "${speedDecimal.format(bytesPerSecond.coerceAtLeast(0L) / 1_000_000.0)}${units.mbPerSecond}"

    /** mm:ss, e.g. 408 s -> "6:48". Used by both players. */
    public fun duration(totalSeconds: Long): String {
        val safe = if (totalSeconds < 0L) 0L else totalSeconds
        val minutes = safe / 60L
        val seconds = safe % 60L
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    /** Millisecond duration for media rows. */
    public fun durationMillis(millis: Long): String = duration(millis / 1000L)

    /** "-6:48" style remaining time shown beside the scrubber. */
    public fun remaining(totalSeconds: Long, elapsedSeconds: Long): String =
        "-${duration((totalSeconds - elapsedSeconds).coerceAtLeast(0L))}"

    /** Locale aware percentage with no decimals. */
    public fun percent(fraction: Float): String =
        "${noDecimal.format((fraction * 100f).coerceIn(0f, 100f).toDouble())}${units.percentSign}"

    /** Medium date + short time, e.g. "12 May 2026, 15:13". */
    public fun dateTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(epochMillis))

    /** Short date only, used for file rows: "12 May 2026". */
    public fun date(epochMillis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(epochMillis))

    /** Short time only, used by history rows: "15:13". */
    public fun time(epochMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(epochMillis))

    /** HH:mm:ss for log rows. */
    public fun logTime(epochMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.MEDIUM, locale).format(Date(epochMillis))

    /** Day header for photo/video groups: Today, Yesterday or a real date. */
    public fun dayLabel(epochMillis: Long, nowEpochMillis: Long = System.currentTimeMillis()): String {
        val daysBetween = calendarDaysBetween(epochMillis, nowEpochMillis)
        return when (daysBetween) {
            0L -> units.today
            1L -> units.yesterday
            else -> date(epochMillis)
        }
    }

    /** Relative "2 min ago" label for recent devices and history rows. */
    public fun relativeTime(epochMillis: Long, nowEpochMillis: Long = System.currentTimeMillis()): String {
        val seconds = ((nowEpochMillis - epochMillis) / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 60L -> units.justNow()
            seconds < 3_600L -> units.minutesAgo(seconds / 60L)
            seconds < 86_400L -> units.hoursAgo(seconds / 3_600L)
            seconds < 2_592_000L -> units.daysAgo(seconds / 86_400L)
            else -> date(epochMillis)
        }
    }

    /** Grouped count, e.g. "1,284" for a WebShare stat card. */
    public fun count(value: Long): String = DecimalFormat("#,##0", DecimalFormatSymbols(locale)).format(value)

    private fun calendarDaysBetween(then: Long, now: Long): Long {
        val zone = java.util.TimeZone.getDefault()
        val offset = zone.getOffset(then).toLong()
        val dayMillis = 86_400_000L
        return ((now + offset) / dayMillis) - ((then + offset) / dayMillis)
    }

    public companion object {
        /** Formatter for the device default locale with English units. */
        public fun forDefaultLocale(units: UnitLabels = UnitLabels.ENGLISH): MorseFormatters =
            MorseFormatters(Locale.getDefault(), units)
    }
}
