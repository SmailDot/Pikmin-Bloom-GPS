package app.pikminbloom.gps.ui

import app.pikminbloom.gps.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spotlight tour: eight steps in order. The menu, start, homes and handle steps point at real things on screen; the
 * map is circled at its centre; the go-modes card shows its demo above the text; the first and last cards are centred.
 */
class CoachStepsTest {
    private val steps = CoachSteps.steps

    @Test
    fun theTourHasEightStepsInOrder() {
        assertEquals(8, steps.size)
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
        assertEquals(TargetKey.START, steps[4].target)
        assertEquals(TargetKey.MENU, steps[5].target)
        assertEquals(TargetKey.HANDLE, steps[6].target)
    }

    @Test
    fun theGoModesStepComesRightAfterTheMapStepAndIsACentredCard() {
        assertEquals(R.string.tour_modes_title, steps[3].titleRes)
        assertNull(steps[3].target)
    }

    @Test
    fun onlyTheGoModesStepShowsTheDemo() {
        assertEquals(listOf(false, false, false, true, false, false, false, false), steps.map { it.demo })
    }

    @Test
    fun theHomesStepComesAfterTheStartStep() {
        assertEquals(R.string.tour_homes_title, steps[5].titleRes)
    }

    @Test
    fun onlyTheTargetedStepsAskForAGesture() {
        assertEquals(
            listOf(
                Gesture.NONE, Gesture.TAP, Gesture.LONG_PRESS, Gesture.NONE,
                Gesture.TAP, Gesture.TAP, Gesture.TAP, Gesture.NONE,
            ),
            steps.map { it.gesture },
        )
    }

    @Test
    fun theMapAndTheHandleAreCircledWhileTheMenuStartAndHomesAreRectangles() {
        assertEquals(SpotShape.RECT, steps[1].shape)
        assertEquals(SpotShape.CIRCLE, steps[2].shape)
        assertEquals(SpotShape.RECT, steps[4].shape)
        assertEquals(SpotShape.RECT, steps[5].shape)
        assertEquals(SpotShape.CIRCLE, steps[6].shape)
    }

    @Test
    fun theStartAndHandleStepsHaveFallbackBodiesForWhenTheyAreNotOnScreen() {
        assertNull(steps[1].fallbackBodyRes)
        assertNull(steps[2].fallbackBodyRes)
        assertNotNull(steps[4].fallbackBodyRes)
        assertNull(steps[5].fallbackBodyRes)
        assertNotNull(steps[6].fallbackBodyRes)
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
