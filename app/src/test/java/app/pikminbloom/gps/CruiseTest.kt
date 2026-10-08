package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.sim.JoystickInput
import app.pikminbloom.gps.sim.WalkSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 定速巡航: lock the pad's heading and keep going after the finger lifts (asked for 2026-10-03). */
class CruiseTest {
    private val here = LatLng(22.6273, 121.1014)
    private val config = PatrolConfig(speedMps = 18.0 / 3.6)
    private val pad = JoystickInput(enabled = true)

    private fun simAt(p: LatLng, cfg: PatrolConfig = config) =
        WalkSimulator(cfg, Random(4)).also { it.load(PatrolPlanner.planReturnHome(p, p)) }

    @Test
    fun releasingTheKnobWithoutCruiseStandsStill() {
        assertEquals(0.0, pad.steer(90.0, 1.0).steer(0.0, 0.0).drive().second, 0.0)
    }

    @Test
    fun withCruiseTheLastHeadingContinuesAtFullSpeedAfterRelease() {
        assertEquals(90.0 to 1.0, pad.steer(90.0, 0.6).withCruise(true).steer(0.0, 0.0).drive())
    }

    @Test
    fun theReleaseEventDoesNotTurnTheLockedHeadingNorth() {   // JoystickView sends (0, 0) on ACTION_UP
        assertEquals(135.0 to 1.0, pad.steer(135.0, 1.0).steer(0.0, 0.0).withCruise(true).drive())
    }

    @Test
    fun steeringWhileCruisingReAimsIt() {
        assertEquals(180.0 to 1.0, pad.steer(90.0, 1.0).withCruise(true).steer(180.0, 0.5).steer(0.0, 0.0).drive())
    }

    @Test
    fun aDeflectedKnobBeatsTheCruiseForThatTick() {
        assertEquals(270.0 to 0.5, pad.steer(90.0, 1.0).withCruise(true).steer(270.0, 0.5).drive())
    }

    @Test
    fun unlockingStops() {
        assertEquals(0.0, pad.steer(45.0, 1.0).withCruise(true).steer(0.0, 0.0).withCruise(false).drive().second, 0.0)
    }

    @Test
    fun aDisabledPadNeverMoves() {
        assertEquals(0.0, pad.steer(45.0, 1.0).withCruise(true).copy(enabled = false).drive().second, 0.0)
    }

    @Test
    fun aKnobReportedPastFullDeflectionIsCappedAtFullSpeed() {
        assertEquals(90.0 to 1.0, pad.steer(90.0, 7.5).drive())
    }

    @Test
    fun aHeadingOutsideTheCompassFoldsBackIntoZeroTo360() {   // the debug broadcast takes any float
        assertEquals(90.0 to 1.0, pad.steer(450.0, 1.0).drive())
        assertEquals(270.0 to 1.0, pad.steer(-90.0, 1.0).drive())
    }

    // G4 review: a lock needs an open pad and a heading, or reopening the pad / locking early walks north at once.

    @Test
    fun aLockBeforeTheKnobWasEverPushedIsRefused() {
        assertFalse("a fresh pad has no heading to lock", pad.withCruise(true).cruise)
        assertFalse("a dead-zone report is not a push", pad.steer(90.0, 0.0).withCruise(true).cruise)
    }

    @Test
    fun aLockOnAClosedPadIsRefused() {
        assertFalse("a closed pad cannot be locked", pad.steer(90.0, 1.0).copy(enabled = false).withCruise(true).cruise)
        // The race: the engine already reset a closed pad, then a tap on its lock lands.
        assertEquals(JoystickInput(), JoystickInput().withCruise(true))
    }

    @Test
    fun aLockAfterAPushIsAcceptedAndCruisesAlongThatPush() {
        val locked = pad.steer(200.0, 0.4).withCruise(true)
        assertTrue("a pushed knob can be locked", locked.cruise)
        assertEquals(200.0 to 1.0, locked.steer(0.0, 0.0).drive())
    }

    @Test
    fun unlockingAlwaysWorksEvenOnAClosedPad() {
        val locked = pad.steer(90.0, 1.0).withCruise(true)
        assertFalse(locked.withCruise(false).cruise)
        assertFalse("a closed pad can still be unlocked", locked.copy(enabled = false).withCruise(false).cruise)
    }

    @Test
    fun aPadWithNoHeadingNeverMovesHoweverItWasBuilt() {   // withCruise refuses it, but copy() / the constructor do not
        assertEquals("a lock built without a push", 0.0, JoystickInput(enabled = true, cruise = true).drive().second, 0.0)
        assertEquals("a deflection built without a heading", 0.0, JoystickInput(enabled = true, magnitude = 1.0).drive().second, 0.0)
    }

    @Test
    fun cruisingOnFootWalksStraightAtTheWalkingSpeed() {
        val sim = simAt(here)
        val (bearing, magnitude) = pad.steer(90.0, 1.0).withCruise(true).steer(0.0, 0.0).drive()
        var moved = 0.0
        repeat(20) { moved += sim.advanceManual(1.0, bearing, magnitude).distanceDeltaM }
        assertEquals(20 * 18.0 / 3.6, moved, 0.01)
        assertEquals(90.0, GeoMath.bearingDeg(here, sim.current().position), 3.0)
    }

    @Test
    fun cruisingOnFootCountsSteps() {
        val (bearing, magnitude) = pad.steer(0.0, 1.0).withCruise(true).steer(0.0, 0.0).drive()
        assertTrue("cruising on foot must produce steps", simAt(here).advanceManual(1.0, bearing, magnitude).countsSteps)
    }

    @Test
    fun cruisingByCarCountsNoSteps() {
        val sim = simAt(here).also { it.travelOverride = TravelMode.CAR }
        val (bearing, magnitude) = pad.steer(0.0, 1.0).withCruise(true).steer(0.0, 0.0).drive()
        assertFalse("a car must not produce steps", sim.advanceManual(1.0, bearing, magnitude).countsSteps)
    }

    @Test
    fun cruisingEastAcrossTheDateLineWrapsTheLongitude() {
        val edge = LatLng(0.0, 179.999)
        val sim = simAt(edge, config.copy(vehicleSpeedsKmh = mapOf(TravelMode.PLANE to 1200.0))).also { it.travelOverride = TravelMode.PLANE }
        val (bearing, magnitude) = pad.steer(90.0, 1.0).withCruise(true).steer(0.0, 0.0).drive()
        repeat(5) { sim.advanceManual(1.0, bearing, magnitude) }
        val lon = sim.current().position.lon
        assertTrue("lon $lon", lon in -180.0..-179.0)
    }
}
