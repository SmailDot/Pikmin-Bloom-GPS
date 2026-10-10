package app.pikminbloom.gps.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The go-modes demo's timing: one loop per mode, matching the three flower menu actions it explains. */
class GoModesDemoTest {

    private fun assertX(expected: Float, actual: Float) = assertEquals(expected.toDouble(), actual.toDouble(), 0.001)

    @Test
    fun goNowWalksToTheFlowerThenKeepsGoingToTheNextOne() {
        assertX(GoModesDemo.FLOWER, GoModesDemo.frame(GoMode.GO_NOW, 0.4f).dotX)
        assertX(GoModesDemo.FAR, GoModesDemo.frame(GoMode.GO_NOW, 0.8f).dotX)
    }

    @Test
    fun goNowNeverPausesOnTheWay() {
        for (i in 0..10) assertFalse(GoModesDemo.frame(GoMode.GO_NOW, i / 10f).paused)
    }

    @Test
    fun goAndStayWalksToTheFlowerBeforePausing() {
        val walking = GoModesDemo.frame(GoMode.GO_AND_STAY, 0.25f)
        assertTrue(walking.dotX < GoModesDemo.FLOWER)
        assertFalse(walking.paused)
    }

    @Test
    fun goAndStayPausesAtTheFlowerOnceItArrives() {
        val arrived = GoModesDemo.frame(GoMode.GO_AND_STAY, 0.6f)
        assertX(GoModesDemo.FLOWER, arrived.dotX)
        assertTrue(arrived.paused)
        assertTrue(GoModesDemo.frame(GoMode.GO_AND_STAY, 1f).paused)
    }

    @Test
    fun teleportVanishesAtTheStartAndReappearsAtTheFlower() {
        assertTrue(GoModesDemo.frame(GoMode.TELEPORT, 0.2f).visible)
        assertX(GoModesDemo.START, GoModesDemo.frame(GoMode.TELEPORT, 0.2f).dotX)
        assertFalse(GoModesDemo.frame(GoMode.TELEPORT, 0.45f).visible)
        val arrived = GoModesDemo.frame(GoMode.TELEPORT, 0.55f)
        assertTrue(arrived.visible)
        assertX(GoModesDemo.FLOWER, arrived.dotX)
    }

    @Test
    fun teleportFlashesAtTheVanishAndTheArrival() {
        assertTrue(GoModesDemo.frame(GoMode.TELEPORT, 0.45f).flash)
        assertTrue(GoModesDemo.frame(GoMode.TELEPORT, 0.55f).flash)
    }

    @Test
    fun teleportStopsFlashingOnceTheAvatarHasSettled() {
        assertFalse(GoModesDemo.frame(GoMode.TELEPORT, 0.7f).flash)
    }

    @Test
    fun progressOutsideTheLoopIsClampedToItsEnds() {
        assertEquals(GoModesDemo.frame(GoMode.GO_NOW, 0f), GoModesDemo.frame(GoMode.GO_NOW, -1f))
        assertEquals(GoModesDemo.frame(GoMode.GO_NOW, 1f), GoModesDemo.frame(GoMode.GO_NOW, 2f))
    }

    @Test
    fun theIntroAboutTheMenuShowsOnceUntilItIsSeen() {
        assertTrue(GoModesDemo.shouldShowIntro(seen = false))
        assertFalse(GoModesDemo.shouldShowIntro(seen = true))
    }
}
