package app.pikminbloom.gps.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spotlight tour: six steps in order. The menu, start and handle steps point at real things on screen; the map is
 * circled at its centre; the first and last cards are centred.
 */
class CoachStepsTest {
    private val steps = CoachSteps.steps

    @Test
    fun theTourHasSixStepsInOrder() {
        assertEquals(6, steps.size)
    }

    @Test
    fun theFirstAndLastCardsAreCentred() {
        assertNull(steps.first().target)
        assertNull(steps.last().target)
    }

    @Test
    fun theMiddleStepsPointAtTheMenuMapStartAndHandle() {
        assertEquals(TargetKey.MENU, steps[1].target)
        assertEquals(TargetKey.MAP, steps[2].target)
        assertEquals(TargetKey.START, steps[3].target)
        assertEquals(TargetKey.HANDLE, steps[4].target)
    }

    @Test
    fun onlyTheTargetedStepsAskForAGesture() {
        assertEquals(
            listOf(Gesture.NONE, Gesture.TAP, Gesture.LONG_PRESS, Gesture.TAP, Gesture.TAP, Gesture.NONE),
            steps.map { it.gesture },
        )
    }

    @Test
    fun theMapAndTheHandleAreCircledWhileTheMenuAndStartAreRectangles() {
        assertEquals(SpotShape.RECT, steps[1].shape)
        assertEquals(SpotShape.CIRCLE, steps[2].shape)
        assertEquals(SpotShape.RECT, steps[3].shape)
        assertEquals(SpotShape.CIRCLE, steps[4].shape)
    }

    @Test
    fun theStartAndHandleStepsHaveFallbackBodiesForWhenTheyAreNotOnScreen() {
        assertNull(steps[1].fallbackBodyRes)
        assertNull(steps[2].fallbackBodyRes)
        assertNotNull(steps[3].fallbackBodyRes)
        assertNotNull(steps[4].fallbackBodyRes)
    }

    @Test
    fun everyStepHasItsOwnTitle() {
        val titles = steps.map { it.titleRes }
        assertEquals(titles.size, titles.distinct().size)
    }

    @Test
    fun theTourOpensOnceAfterTheDisclaimerWhenItHasNotBeenSeen() {
        assertTrue(CoachSteps.shouldShowGuide(disclaimerAccepted = true, guideSeen = false))
    }

    @Test
    fun theTourWaitsForTheDisclaimer() {
        assertFalse(CoachSteps.shouldShowGuide(disclaimerAccepted = false, guideSeen = false))
    }

    @Test
    fun theTourIsNotShownAgainOnceSeen() {
        assertFalse(CoachSteps.shouldShowGuide(disclaimerAccepted = true, guideSeen = true))
        assertFalse(CoachSteps.shouldShowGuide(disclaimerAccepted = false, guideSeen = true))
    }
}
