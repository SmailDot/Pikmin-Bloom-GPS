package app.pikminbloom.gps

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The navigation bar's height reaches the auto runs and the run header says what it was. Like the other wiring tests,
 * these read the sources: the bar itself needs a phone with 3-button navigation.
 */
class NavBarWiringTest {

    private fun source(path: String): String =
        listOf(File("src/main/java/app/pikminbloom/gps/$path"), File("app/src/main/java/app/pikminbloom/gps/$path"))
            .first { it.exists() }.readText()

    @Test
    fun theExpeditionRunTakesTheBarFromTheServiceAndLogsIt() {
        val runner = source("expedition/ExpeditionRunner.kt")
        assertTrue("the expedition run does not read the navigation bar", runner.contains("bottomInsetFraction()"))
        assertTrue("the expedition header does not log the bar", runner.contains("navInset="))
    }

    @Test
    fun theFeedRunTakesTheBarFromTheServiceAndLogsIt() {
        val runner = source("feed/FeedRunner.kt")
        assertTrue("the feed run does not read the navigation bar", runner.contains("bottomInsetFraction()"))
        assertTrue("the feed header does not log the bar", runner.contains("navInset="))
    }

    @Test
    fun theExpeditionHeaderIsWrittenWithTheScreenshotTheRunReceived() {
        val runner = source("expedition/ExpeditionRunner.kt")
        assertTrue("the header does not say the screenshot size", runner.contains("shot=\$shot"))
        assertTrue("the settle looks are not wired into the run", source("expedition/ExpeditionSession.kt").contains("list: still after"))
    }

    @Test
    fun theServiceReadsTheNavigationBarFromItsWindowInsets() {
        val service = source("nectar/NectarAccessibilityService.kt")
        assertTrue("the service does not read the navigation bar inset", service.contains("getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())"))
    }
}
