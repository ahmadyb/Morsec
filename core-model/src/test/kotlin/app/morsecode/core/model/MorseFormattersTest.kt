package app.morsecode.core.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mockup's own formatters (`fmtMB`, `fmtSpd`, `fmtTime`) are the reference
 * for these thresholds, so the numbers a user reads on Android match the numbers
 * in the approved design document.
 */
class MorseFormattersTest {

    private val formats = MorseFormatters(Locale.ROOT, UnitLabels.ENGLISH)

    @Test
    fun `bytes follow the mockup thresholds`() {
        assertEquals("412 KB", formats.bytes(412_000L))
        assertEquals("2.4 MB", formats.bytes(2_400_000L))
        assertEquals("4.1 MB", formats.bytes(4_100_000L))
        assertEquals("6.5 MB", formats.bytes(6_500_000L))
        // 10 MB and above drop the decimal, exactly like fmtMB().
        assertEquals("18 MB", formats.bytes(18_200_000L))
        assertEquals("144 MB", formats.bytes(144_000_000L))
        assertEquals("131 MB", formats.bytes(131_100_000L))
        assertEquals("1.4 GB", formats.bytes(1_400_000_000L))
        assertEquals("43.6 GB", formats.bytes(43_600_000_000L))
        assertEquals("104.2 GB", formats.bytes(104_200_000_000L))
    }

    @Test
    fun `negative and zero byte counts never produce nonsense`() {
        assertEquals("0 KB", formats.bytes(0L))
        assertEquals("0 KB", formats.bytes(-5L))
    }

    @Test
    fun `progress pairs two byte labels`() {
        assertEquals("13 MB / 18 MB", formats.progress(13_100_000L, 18_200_000L))
        assertEquals("0 KB / 4.1 MB", formats.progress(0L, 4_100_000L))
    }

    @Test
    fun `speed always keeps one decimal`() {
        assertEquals("6.2 MB/s", formats.speed(6_200_000L))
        assertEquals("0.0 MB/s", formats.speed(0L))
        assertEquals("14.2 MB/s", formats.speed(14_200_000L))
    }

    @Test
    fun `durations are mm ss`() {
        assertEquals("0:48", formats.duration(48L))
        assertEquals("2:57", formats.duration(177L))
        assertEquals("4:08", formats.duration(248L))
        assertEquals("6:48", formats.duration(408L))
        assertEquals("24:12", formats.duration(1452L))
        assertEquals("0:00", formats.duration(-3L))
    }

    @Test
    fun `remaining time is negative and clamped`() {
        assertEquals("-2:24", formats.remaining(248L, 104L))
        assertEquals("-0:00", formats.remaining(100L, 500L))
    }

    @Test
    fun `percent is locale aware and clamped`() {
        assertEquals("34%", formats.percent(0.34f))
        assertEquals("100%", formats.percent(1.4f))
        assertEquals("0%", formats.percent(-0.2f))
        val german = MorseFormatters(Locale.GERMANY, UnitLabels.ENGLISH)
        assertEquals("34%", german.percent(0.34f))
    }

    @Test
    fun `decimal separator follows the locale for byte labels`() {
        val german = MorseFormatters(Locale.GERMANY, UnitLabels.ENGLISH)
        assertEquals("2,4 MB", german.bytes(2_400_000L))
        assertEquals("6,2 MB/s", german.speed(6_200_000L))
    }

    @Test
    fun `relative time uses the supplied labels`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", formats.relativeTime(now - 30_000L, now))
        assertEquals("2 min ago", formats.relativeTime(now - 120_000L, now))
        assertEquals("3 h ago", formats.relativeTime(now - 3 * 3_600_000L, now))
        assertEquals("1 day ago", formats.relativeTime(now - 86_400_000L, now))
        assertEquals("4 days ago", formats.relativeTime(now - 4 * 86_400_000L, now))
    }

    @Test
    fun `day labels distinguish today and yesterday from real dates`() {
        val now = 1_700_000_000_000L
        assertEquals("Today", formats.dayLabel(now - 60_000L, now))
        assertEquals("Yesterday", formats.dayLabel(now - 86_400_000L, now))
        val older = formats.dayLabel(now - 40L * 86_400_000L, now)
        assertTrue("expected a formatted date but got $older", older.isNotEmpty() && older != "Today")
    }

    @Test
    fun `counts are grouped`() {
        assertEquals("1,284", formats.count(1284L))
        assertEquals("412", formats.count(412L))
    }

    @Test
    fun `english labels pluralise days only`() {
        assertEquals("1 day ago", UnitLabels.ENGLISH.daysAgo(1L))
        assertEquals("2 days ago", UnitLabels.ENGLISH.daysAgo(2L))
    }
}
