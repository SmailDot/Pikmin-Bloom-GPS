package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.ui.OverlayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The floating bar's step figure restarts at local midnight (reported 2026-10-03). */
class OverlayStatusTest {
    @Test
    fun afterMidnightTheBarShowsTheNewDaysCountNotTheSessionTotal() {
        val justAfterMidnight = PatrolState(phase = PatrolPhase.WALKING, sessionSteps = 61_234, stepsWrittenToday = 312)
        assertEquals(312L, OverlayStatus.stepsToday(justAfterMidnight, injectSteps = true))
    }

    @Test
    fun withStepWritingOffTheBarShowsNoStepFigure() {
        assertNull(OverlayStatus.stepsToday(PatrolState(phase = PatrolPhase.WALKING, sessionSteps = 500), injectSteps = false))
    }
}
