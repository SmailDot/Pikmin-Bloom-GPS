package app.pikminbloom.gps.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tour rings the floating bar's real handle. The bar is its own window, and the position saved in prefs is not
 * where the handle is drawn (the status bar sits between them), so the bar publishes the handle's on-screen bounds and
 * the tour uses those. Like SpotlightSafetyTest, these read the sources.
 */
class FloatingBarHandleTest {

    private fun source(file: String): String =
        listOf(File("src/main/java/app/pikminbloom/gps/ui/$file"), File("app/src/main/java/app/pikminbloom/gps/ui/$file"))
            .first { it.exists() }.readText()

    @Test
    fun theBarPublishesTheHandlesScreenBounds() {
        assertTrue("OverlayService does not publish the handle's bounds", source("OverlayService.kt").contains("var handleBounds: Bounds?"))
    }

    @Test
    fun theBarRepublishesTheHandleWhenItLaysOut() {
        val overlay = source("OverlayService.kt")
        assertTrue("the handle's bounds are not republished on layout", overlay.contains("addOnLayoutChangeListener"))
    }

    @Test
    fun theBarRepublishesTheHandleAfterADrag() {
        val save = source("OverlayService.kt").substringAfter("private fun savePosition()").substringBefore("}")
        assertTrue("the handle's bounds are not republished when a drag ends", save.contains("publishHandleBounds()"))
    }

    @Test
    fun theBarClearsThePublishedBoundsWhenItIsDestroyed() {
        val destroy = source("OverlayService.kt").substringAfter("override fun onDestroy()").substringBefore("super.onDestroy()")
        assertTrue("onDestroy leaves stale handle bounds for the tour to ring", destroy.contains("handleBounds = null"))
    }

    @Test
    fun theTourRingsThePublishedHandleBeforeItsPrefsGuess() {
        assertTrue(
            "MainActivity ignores the published handle bounds",
            source("MainActivity.kt").contains("OverlayService.handleBounds ?:"),
        )
    }

    @Test
    fun theTourConvertsItsSpotsToTheContentRootExactlyOnce() {
        assertTrue("MainActivity does not convert the spots to the content root", source("MainActivity.kt").contains("android.R.id.content"))
        assertFalse("SpotlightView converts the spots again, moving the ring off its target", source("SpotlightView.kt").contains("getLocationOnScreen"))
    }
}
