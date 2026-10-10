package app.pikminbloom.gps.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The go-modes explanation is on screen in three places: the tour card, the one-time popup on the first flower tap, and
 * the flower menu's own lines. These check each place is wired, reading the sources the way SpotlightSafetyTest does.
 */
class GoModesWiringTest {

    private fun source(file: String): String =
        listOf(File("src/main/java/app/pikminbloom/gps/ui/$file"), File("app/src/main/java/app/pikminbloom/gps/ui/$file"))
            .first { it.exists() }.readText()

    private fun layout(file: String): String =
        listOf(File("src/main/res/layout/$file"), File("app/src/main/res/layout/$file")).first { it.exists() }.readText()

    @Test
    fun theFirstFlowerTapShowsTheExplanationOnceBeforeTheMenu() {
        val main = source("MainActivity.kt")
        assertTrue("the flower tap does not check the once-only explanation", main.contains("GoModesDemo.shouldShowIntro(prefs.goModesSeen)"))
        assertTrue("the flower tap does not open the explanation", main.contains("WaypointDialogs.showGoModesIntro("))
    }

    @Test
    fun theFlowerMenuItemsCarryTheirOneLineHints() {
        val dialogs = source("WaypointDialogs.kt")
        assertTrue("the menu items show no hints", dialogs.contains("R.string.hint_go_now") && dialogs.contains("R.string.hint_teleport_here"))
    }

    @Test
    fun theTourCardShowsTheDemoOnlyForItsStep() {
        assertTrue("SpotlightView ignores the demo flag of a step", source("SpotlightView.kt").contains("step.demo"))
    }

    @Test
    fun theTourCardHasAPlaceForTheDemoAboveItsText() {
        assertTrue("coach_card.xml has no demo view", layout("coach_card.xml").contains("coachDemo"))
    }
}
