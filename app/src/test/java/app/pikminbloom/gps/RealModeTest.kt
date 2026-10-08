package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.service.RealMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真實位置 ⇄ 回到虛擬位置 (PatrolPhase.SUSPENDED), asked for 2026-10-03. */
class RealModeTest {
    @Test
    fun everyPhaseWithAVirtualPositionCanSwitchToReal() {
        for (p in listOf(PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.PAUSED, PatrolPhase.PARKED, PatrolPhase.MANUAL, PatrolPhase.RETURNING_HOME)) {
            assertTrue(p.name, RealMode.canEnter(p))
        }
    }

    @Test
    fun phasesWithoutAVirtualPositionCannot() {
        for (p in listOf(PatrolPhase.IDLE, PatrolPhase.STARTING, PatrolPhase.STOPPING, PatrolPhase.SUSPENDED)) {
            assertFalse(p.name, RealMode.canEnter(p))
        }
    }

    @Test
    fun goingBackRestoresTheParkedHome() {
        assertEquals(PatrolPhase.PARKED, RealMode.phaseOnLeave(PatrolPhase.PARKED, joystickEnabled = false))
    }

    @Test
    fun aPausedPatrolStaysPausedEvenWithThePadUp() {
        assertEquals(PatrolPhase.PAUSED, RealMode.phaseOnLeave(PatrolPhase.PAUSED, joystickEnabled = true))
    }

    @Test
    fun aPadStillUpTakesOverAMovingPhase() {
        assertEquals(PatrolPhase.MANUAL, RealMode.phaseOnLeave(PatrolPhase.WALKING, joystickEnabled = true))
    }

    @Test
    fun aPadStillUpTakesOverEveryOtherPhaseItCouldSteerToo() {
        // As PatrolService.onJoystickToggled: an orbit, a parked avatar and a walk home are interrupted as well.
        for (p in listOf(PatrolPhase.DWELLING, PatrolPhase.PARKED, PatrolPhase.RETURNING_HOME, PatrolPhase.MANUAL)) {
            assertEquals(p.name, PatrolPhase.MANUAL, RealMode.phaseOnLeave(p, joystickEnabled = true))
        }
    }

    @Test
    fun aPadPutAwayMeanwhileHandsTheWalkBackToTheRoute() {
        assertEquals(PatrolPhase.WALKING, RealMode.phaseOnLeave(PatrolPhase.MANUAL, joystickEnabled = false))
    }

    @Test
    fun anUnknownPreviousPhaseFallsBackToWalking() {
        assertEquals(PatrolPhase.WALKING, RealMode.phaseOnLeave(null, joystickEnabled = false))
    }

    @Test
    fun everyOtherPhaseComesBackUnchangedWithThePadDown() {
        for (p in listOf(PatrolPhase.RETURNING_HOME, PatrolPhase.DWELLING, PatrolPhase.WALKING, PatrolPhase.PAUSED)) {
            assertEquals(p.name, p, RealMode.phaseOnLeave(p, joystickEnabled = false))
        }
    }

    @Test
    fun aResumeSentMeanwhileCarriesOverToAPatrolThatLeftPaused() {
        // NectarRunner pauses for its run and resumes after it; 真實位置 in between must not leave it paused.
        assertTrue(RealMode.resumeCarriesOver(PatrolPhase.PAUSED, resume = true))
    }

    @Test
    fun aPauseSentMeanwhileCarriesNoResumeOver() {
        assertFalse(RealMode.resumeCarriesOver(PatrolPhase.PAUSED, resume = false))
    }

    @Test
    fun aResumeSentMeanwhileMeansNothingToAPatrolThatWasNotPaused() {
        // PARKED included: a 繼續 there starts a new lap, which nobody asked for on the way back.
        for (p in listOf(PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.MANUAL, PatrolPhase.PARKED, PatrolPhase.RETURNING_HOME, null)) {
            assertFalse(p?.name ?: "null", RealMode.resumeCarriesOver(p, resume = true))
        }
    }
}
