package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.ui.StopConfirm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The floating bar's 停止 stopped on the first touch (2026-10-08): every running phase now asks first. */
class StopConfirmTest {
    @Test
    fun everyRunningPhaseAsks() {
        for (p in PatrolPhase.entries.filter { it != PatrolPhase.IDLE && it != PatrolPhase.STOPPING }) {
            assertNotNull("$p must ask before stopping", StopConfirm.forPhase(p))
        }
    }

    @Test
    fun nothingToStopNothingToAsk() {
        assertNull(StopConfirm.forPhase(PatrolPhase.IDLE))
        assertNull(StopConfirm.forPhase(PatrolPhase.STOPPING))
    }

    @Test
    fun onTheRouteItSuggestsGoingHomeFirst() {
        for (p in listOf(PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.PAUSED, PatrolPhase.MANUAL)) {
            assertEquals("$p", StopConfirm.SUGGEST_HOME, StopConfirm.forPhase(p))
        }
    }

    @Test
    fun theOtherPhasesSayWhatStoppingMeansThere() {
        assertEquals(StopConfirm.PARKED, StopConfirm.forPhase(PatrolPhase.PARKED))
        assertEquals(StopConfirm.RETURNING, StopConfirm.forPhase(PatrolPhase.RETURNING_HOME))
        assertEquals(StopConfirm.REAL, StopConfirm.forPhase(PatrolPhase.SUSPENDED))
        assertEquals(StopConfirm.STARTING, StopConfirm.forPhase(PatrolPhase.STARTING))
    }
}
