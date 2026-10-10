package app.pikminbloom.gps.ui

import app.pikminbloom.gps.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spotlight tour: seven steps in order. The menu, start, homes and handle steps point at real things on screen; the
 * map is circled at its centre; the first and last cards are centred.
 */
class CoachStepsTest {
    private val steps = CoachSteps.steps

    @Test
    fun theTourHasSevenStepsInOrder() {
        assertEquals(7, steps.size)
    }

    @Test
    fun theFirstAndLastCardsAreCentred() {
        assertNull(steps.first().target)
        assertNull(steps.last().target)
    }

    @Test
    fun theMiddleStepsPointAtTheMenuMapStartHomesAndHandle() {
        assertEquals(TargetKey.MENU, steps[1].target)
        assertEquals(TargetKey.MAP, steps[2].target)
        assertEquals(TargetKey.START, steps[3].target)
        assertEquals(TargetKey.MENU, steps[4].target)
        assertEquals(TargetKey.HANDLE, steps[5].target)
    }

    @Test
    fun theHomesStepComesAfterTheStartStep() {
        assertEquals(R.string.tour_homes_title, steps[4].titleRes)
    }

    @Test
    fun onlyTheTargetedStepsAskForAGesture() {
        assertEquals(
            listOf(Gesture.NONE, Gesture.TAP, Gesture.LONG_PRESS, Gesture.TAP, Gesture.TAP, Gesture.TAP, Gesture.NONE),
            steps.map { it.gesture },
        )
    }

    @Test
    fun theMapAndTheHandleAreCircledWhileTheMenuStartAndHomesAreRectangles() {
        assertEquals(SpotShape.RECT, steps[1].shape)
        assertEquals(SpotShape.CIRCLE, steps[2].shape)
        assertEquals(SpotShape.RECT, steps[3].shape)
        assertEquals(SpotShape.RECT, steps[4].shape)
        assertEquals(SpotShape.CIRCLE, steps[5].shape)
    }

    @Test
    fun theStartAndHandleStepsHaveFallbackBodiesForWhenTheyAreNotOnScreen() {
        assertNull(steps[1].fallbackBodyRes)
        assertNull(steps[2].fallbackBodyRes)
        assertNotNull(steps[3].fallbackBodyRes)
        assertNull(steps[4].fallbackBodyRes)
        assertNotNull(steps[5].fallbackBodyRes)
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
