package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.service.AppEvent
import app.pikminbloom.gps.service.ForegroundWatch
import app.pikminbloom.gps.service.RealMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 9c: switch to the real position on the way INTO Google Maps, never back (2026-10-03). */
class ForegroundWatchTest {
    private val maps = ForegroundWatch.GOOGLE_MAPS
    private val game = "com.nianticlabs.pikmin"

    @Test
    fun theLatestResumedActivityIsInFront() {
        assertEquals(maps, ForegroundWatch.foreground(listOf(AppEvent(game, 1_000L), AppEvent(maps, 2_000L)), previous = null))
    }

    @Test
    fun theLatestEventWinsWhateverOrderTheyComeIn() {
        assertEquals(maps, ForegroundWatch.foreground(listOf(AppEvent(maps, 2_000L), AppEvent(game, 1_000L)), previous = null))
    }

    @Test
    fun noEventsMeansWhoeverWasInFrontStillIs() {
        assertEquals(game, ForegroundWatch.foreground(emptyList(), previous = game))
    }

    @Test
    fun openingGoogleMapsDuringAWalkAsks() {
        assertTrue(ForegroundWatch.shouldAskForReal(previous = game, current = maps, phase = PatrolPhase.WALKING))
    }

    @Test
    fun openingGoogleMapsAsksInExactlyThePhasesRealModeCanBeEntered() {
        // Mirrors RealModeTest's phase lists without repeating them: the rule is RealMode.canEnter, nothing of its own.
        for (p in PatrolPhase.values()) {
            assertEquals(p.name, RealMode.canEnter(p), ForegroundWatch.shouldAskForReal(previous = game, current = maps, phase = p))
        }
    }

    @Test
    fun stayingInGoogleMapsNeverAsksAgain() {
        assertFalse(ForegroundWatch.shouldAskForReal(previous = maps, current = maps, phase = PatrolPhase.WALKING))
    }

    @Test
    fun leavingGoogleMapsTriggersNothing() {
        assertFalse(ForegroundWatch.shouldAskForReal(previous = maps, current = game, phase = PatrolPhase.SUSPENDED))
    }

    @Test
    fun anotherAppComingToTheFrontTriggersNothing() {
        // Only Google Maps counts: a browser or the launcher must not take the game's position away.
        assertFalse("a browser in front is not Google Maps", ForegroundWatch.shouldAskForReal(previous = game, current = "com.android.chrome", phase = PatrolPhase.WALKING))
    }

    @Test
    fun aPatrolThatCannotSwitchIsLeftAlone() {
        assertFalse(ForegroundWatch.shouldAskForReal(previous = game, current = maps, phase = PatrolPhase.STARTING))
    }

    @Test
    fun leavingGoogleMapsIsNoticedOnlyOnTheWayOut() {
        // 2026-10-08: the question is dismissed when Maps goes to the back, so it never pops up over the game later.
        assertTrue(ForegroundWatch.leftMaps(previous = maps, current = game))
        assertFalse("still in Maps", ForegroundWatch.leftMaps(previous = maps, current = maps))
        assertFalse("never was in Maps", ForegroundWatch.leftMaps(previous = game, current = "com.android.chrome"))
    }
}
