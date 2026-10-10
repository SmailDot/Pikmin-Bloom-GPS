package app.pikminbloom.gps

import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.support.CrashLog
import app.pikminbloom.gps.support.FeedbackInfo
import app.pikminbloom.gps.support.FeedbackKind
import app.pikminbloom.gps.support.FeedbackReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.time.ZoneOffset

/** 回報問題／建議 (2026-10-09): what goes into a report, and that no position ever does. */
class FeedbackReportTest {
    private val trace = (listOf("java.lang.IllegalStateException: bad fix LatLng(35.681236, 139.767125)") +
        (1..80).map { "\tat app.pikminbloom.gps.Some.method(Some.kt:$it)" }).joinToString("\n")
    private val crash = CrashLog.Crash(atMs = 0L, version = "1.5.0", trace = trace)
    private val info = FeedbackInfo(
        app = "1.5.0 (7, release)", android = "14 (API 34)", device = "Xiaomi 23078PND5G", language = "app en / phone en-US",
        patrol = "WALKING", lastError = "no fix near 25.033964,121.564468", lastExit = "CRASH · 2026-10-08 23:10", crash = crash,
    )

    @Test
    fun coordinatesAreMaskedButVersionsAndLineNumbersStay() {
        assertEquals("LatLng(#.####, #.####)", FeedbackReport.redact("LatLng(35.681236, 139.767125)"))
        assertEquals("1.5.0 (Some.kt:123) 4.7 km/h", FeedbackReport.redact("1.5.0 (Some.kt:123) 4.7 km/h"))
        val body = FeedbackReport.body(FeedbackKind.BUG, info, Lang.EN, zone = ZoneOffset.UTC)
        assertFalse(body.contains("35.681236") || body.contains("121.564468"))
    }

    @Test
    fun aBugReportCarriesTheDiagnosticsAndAShortenedTrace() {
        val body = FeedbackReport.body(FeedbackKind.BUG, info, Lang.EN, zone = ZoneOffset.UTC)
        assertTrue(body.startsWith("What happened"))
        listOf("App: 1.5.0 (7, release)", "Android: 14 (API 34)", "Phone: Xiaomi 23078PND5G", "Patrol: WALKING",
            "How the app last ended: CRASH · 2026-10-08 23:10", "Crash (1970-01-01 00:00, 1.5.0):").forEach {
            assertTrue(it, body.contains(it))
        }
        assertTrue("trace cut to MAX_TRACE_LINES", body.contains("… (+${81 - FeedbackReport.MAX_TRACE_LINES})"))
    }

    @Test
    fun aSuggestionLeavesTheErrorsOut() {
        val body = FeedbackReport.body(FeedbackKind.IDEA, info, Lang.JA, zone = ZoneOffset.UTC)
        assertTrue(body.startsWith("ご提案："))
        assertTrue(body.contains("App: 1.5.0 (7, release)"))
        assertFalse(body.contains("WALKING") || body.contains("IllegalStateException"))
        assertEquals("[提案] Pikmin Bloom GPS 1.5.0", FeedbackReport.subject(FeedbackKind.IDEA, "1.5.0", Lang.JA))
        assertEquals("[建議] Pikmin Bloom GPS 1.5.0", FeedbackReport.subject(FeedbackKind.IDEA, "1.5.0", Lang.ZH))
    }

    @Test
    fun theGithubLinkFitsAndKeepsTheStartOfTheTrace() {
        val long = info.copy(crash = crash.copy(trace = trace + "\n" + (1..400).joinToString("\n") { "\tat x.Y.z(Y.kt:$it)" }))
        val url = FeedbackReport.githubUrl(FeedbackKind.BUG, long, Lang.EN, ZoneOffset.UTC)
        assertTrue(url.startsWith(FeedbackReport.NEW_ISSUE_URL + "?title="))
        assertTrue(url.length <= FeedbackReport.MAX_URL_CHARS)
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(body.contains("IllegalStateException"))
        assertTrue(URLDecoder.decode(url.substringAfter("title=").substringBefore("&"), "UTF-8").startsWith("[Bug] Pikmin Bloom GPS 1.5.0"))
    }

    @Test
    fun theMailtoLinkEncodesSpacesAsPercent20() {
        val uri = FeedbackReport.mailtoUri(FeedbackKind.IDEA, info, Lang.EN, ZoneOffset.UTC)
        assertTrue(uri.startsWith("mailto:${FeedbackReport.EMAIL}?subject=%5BSuggestion%5D%20Pikmin%20Bloom%20GPS%201.5.0&body="))
        assertFalse(uri.contains("+"))
    }

    private val autoLog = "=== expedition run, target=POT, max=10, app 1.5.0, Android 14/API 34, screen 1220x2712, density 420, locale zh-TW\n" +
        "00:00:01.000 list: tabY=2400, cells=[POT×1, FRUIT×0, GIFT×0, COVERED×0, IN_PROGRESS×0, UNKNOWN×0]\n" +
        "00:00:02.000 tap cell POT at 226,1000\n" +
        "00:00:09.000 stopped: LIMIT_REACHED after 1"

    @Test
    fun aBugReportCarriesTheLastAutoRunLogUnderItsHeading() {
        val body = FeedbackReport.body(FeedbackKind.BUG, info.copy(autoRunLog = autoLog), Lang.ZH, zone = ZoneOffset.UTC)
        assertTrue(body.contains("最近一次自動操作紀錄"))
        assertTrue(body.contains("=== expedition run, target=POT"))
        assertTrue(body.contains("stopped: LIMIT_REACHED after 1"))
    }

    @Test
    fun theAutoRunLogHeadingIsEnglishInEnglish() {
        val body = FeedbackReport.body(FeedbackKind.BUG, info.copy(autoRunLog = autoLog), Lang.EN, zone = ZoneOffset.UTC)
        assertTrue(body.contains("Last auto-run log"))
    }

    @Test
    fun aBugReportWithoutARunHasNoAutoRunSection() {
        val body = FeedbackReport.body(FeedbackKind.BUG, info, Lang.ZH, zone = ZoneOffset.UTC)
        assertFalse(body.contains("最近一次自動操作紀錄"))
    }

    @Test
    fun aSuggestionLeavesTheAutoRunLogOut() {
        val body = FeedbackReport.body(FeedbackKind.IDEA, info.copy(autoRunLog = autoLog), Lang.ZH, zone = ZoneOffset.UTC)
        assertFalse(body.contains("最近一次自動操作紀錄") || body.contains("stopped:"))
    }

    @Test
    fun theMailtoBodyCarriesTheAutoRunLog() {
        val uri = FeedbackReport.mailtoUri(FeedbackKind.BUG, info.copy(autoRunLog = autoLog), Lang.EN, ZoneOffset.UTC)
        assertTrue(URLDecoder.decode(uri.substringAfter("&body="), "UTF-8").contains("Last auto-run log"))
    }

    @Test
    fun theGithubLinkLeavesALongAutoRunLogOutRatherThanCuttingTheLinkMidEscape() {
        val long = info.copy(autoRunLog = "=== feed run, target=3\n" + (1..400).joinToString("\n") { "第 $it 次 測試 done" })
        val url = FeedbackReport.githubUrl(FeedbackKind.BUG, long, Lang.ZH, ZoneOffset.UTC)
        assertTrue(url.length <= FeedbackReport.MAX_URL_CHARS)
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8") // throws if a %-escape was cut
        assertFalse(body.contains("最近一次自動操作紀錄"))
        assertTrue(body.contains("閃退紀錄"))
    }

    @Test
    fun crashFileRoundTrip() {
        val text = CrashLog.format(1_728_000_000_000L, "1.5.0", "main", "java.lang.RuntimeException: boom\n\tat a.B.c(B.kt:1)")
        val parsed = CrashLog.parse(text)!!
        assertEquals(1_728_000_000_000L, parsed.atMs)
        assertEquals("1.5.0", parsed.version)
        assertTrue(parsed.trace.startsWith("java.lang.RuntimeException: boom"))
        assertNull("garbage", CrashLog.parse("not a crash file"))
        assertNull("no trace", CrashLog.parse("at=1\nversion=1.5.0\nthread=main\n\n"))
        assertEquals(CrashLog.MAX_CHARS, CrashLog.format(0L, "v", "t", "x".repeat(50_000)).substringAfter("\n\n").length)
    }
}
