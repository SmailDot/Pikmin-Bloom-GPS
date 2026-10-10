package app.pikminbloom.gps.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The auto-run diagnostic log: line and header format, the rotation (the last runs, then the size cap), and the report section. */
class AutoRunLogTest {

    @Test
    fun aLineCarriesTheTimeToTheMillisecond() {
        assertEquals("00:00:01.234 tap cell POT at 226,1000", AutoRunLog.line(1_234L, ZoneOffset.UTC, "tap cell POT at 226,1000"))
    }

    @Test
    fun aLineStaysOneLineEvenWhenItsTextHasNewlines() {
        assertEquals("00:00:00.000 first second", AutoRunLog.line(0L, ZoneOffset.UTC, "first\nsecond"))
    }

    @Test
    fun aRunStartsWithAHeaderNamingTheRunAndThePhone() {
        assertEquals(
            "=== expedition run, target=FRUIT, max=10, app 1.6.5, Android 14/API 34, screen 1080x2400, density 420, locale zh-TW",
            AutoRunLog.header("expedition", "target=FRUIT, max=10", "1.6.5", "14", 34, 1080, 2400, 420, "zh-TW"),
        )
    }

    @Test
    fun theLastThreeRunsAreKeptAndTheOlderOnesAreDropped() {
        val text = (1..4).joinToString("") { "=== run $it\nline $it\n" }
        assertEquals("=== run 2\nline 2\n=== run 3\nline 3\n=== run 4\nline 4\n", AutoRunLog.trimmed(text))
    }

    @Test
    fun aLogPastTheSizeCapLosesItsOlderLinesAndStillStartsAtALineStart() {
        val text = "=== run 1\n" + (1..10_000).joinToString("") { "line $it padding padding\n" }
        val kept = AutoRunLog.trimmed(text)
        assertTrue("kept ${kept.length} chars", kept.length <= AutoRunLog.MAX_BYTES)
        assertTrue(kept.startsWith("line "))
        assertTrue(kept.endsWith("line 10000 padding padding\n"))
    }

    @Test
    fun theReportGetsTheLastRunFromItsHeaderWithoutTheTrailingNewline() {
        val text = "=== run 1\nold line\n=== run 2\nnew one\nnew two\n"
        assertEquals("=== run 2\nnew one\nnew two", AutoRunLog.lastRun(text))
    }

    @Test
    fun aLogWithNoRunHasNoReportSection() {
        assertNull(AutoRunLog.lastRun("00:00:00.000 orphan line\n"))
    }

    @Test
    fun aLongReportSectionKeepsItsHeaderAndTheNewestLinesWithinSixKilobytes() {
        val text = "=== feed run, target=3\n" + (1..5_000).joinToString("\n") { "step $it done" } + "\n"
        val section = AutoRunLog.lastRun(text)!!
        assertTrue("${AutoRunLog.utf8Bytes(section)} bytes", AutoRunLog.utf8Bytes(section) <= AutoRunLog.REPORT_MAX_BYTES)
        assertTrue(section.startsWith("=== feed run, target=3\n"))
        assertTrue(section.endsWith("step 5000 done"))
    }

    @Test
    fun theReportCapCountsUtf8BytesSoChineseTextIsNotLetOffWithThreeTimesTheRoom() {
        val text = "=== expedition run\n" + (1..2_000).joinToString("\n") { "第 $it 次 結果" }
        val section = AutoRunLog.lastRun(text)!!
        assertTrue("${AutoRunLog.utf8Bytes(section)} bytes", AutoRunLog.utf8Bytes(section) <= AutoRunLog.REPORT_MAX_BYTES)
        assertTrue(section.startsWith("=== expedition run\n"))
    }
}
