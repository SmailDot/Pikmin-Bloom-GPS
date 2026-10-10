package app.pikminbloom.gps.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spotlight tour: five steps in order; the middle three point at real controls and ask for a gesture. */
class CoachStepsTest {
    private val steps = CoachSteps.steps

    @Test
    fun theTourHasFiveStepsInOrder() {
        assertEquals(5, steps.size)
    }

    @Test
    fun theFirstAndLastStepsAreCentredCards() {
        assertNull(steps.first().target)
        assertNull(steps.last().target)
    }

    @Test
    fun theMiddleStepsPointAtTheMenuMapAndStartControls() {
        assertEquals(TargetKey.MENU, steps[1].target)
        assertEquals(TargetKey.MAP, steps[2].target)
        assertEquals(TargetKey.START, steps[3].target)
    }

    @Test
    fun onlyTheMiddleStepsAskForAGesture() {
        assertEquals(
            listOf(Gesture.NONE, Gesture.TAP, Gesture.LONG_PRESS, Gesture.TAP, Gesture.NONE),
            steps.map { it.gesture },
        )
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
