package app.pikminbloom.gps.ui

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The tour must never press the controls underneath it: an existing user can see it once after an update, possibly
 * while a patrol is paused, and a forwarded tap on 開始巡邏 or on the map would really act. These guards read the
 * sources the same way RetiredSettingsTest reads the XML.
 */
class SpotlightSafetyTest {

    private fun source(file: String): String =
        listOf(File("src/main/java/app/pikminbloom/gps/ui/$file"), File("app/src/main/java/app/pikminbloom/gps/ui/$file"))
            .first { it.exists() }.readText()

    private fun layout(file: String): String =
        listOf(File("src/main/res/layout/$file"), File("app/src/main/res/layout/$file")).first { it.exists() }.readText()

    @Test
    fun theTourNeverForwardsATouchToAViewUnderneath() {
        val view = source("SpotlightView.kt")
        assertFalse("SpotlightView forwards touches with dispatchTouchEvent: it would press the real controls", view.contains("dispatchTouchEvent"))
        assertFalse("SpotlightView rebuilds touch events for another view", view.contains("MotionEvent.obtain"))
    }

    @Test
    fun theCardIsNotClickableSoATouchOnItAdvancesTheTourInsteadOfStopping() {
        val card = layout("coach_card.xml")
        assertFalse("coach_card.xml is clickable: its touches would not advance the tour", card.contains("android:clickable=\"true\""))
    }
}
