package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.PatrolPlan
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.route.SegmentKind
import app.pikminbloom.gps.sim.WalkSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The simulator side of the mid-patrol controls: a speed change from Settings, a vehicle
 * override, the joystick, and the "keep the leg in progress" helper a waypoint edit relies on.
 */
class LiveControlsTest {

    private val home = LatLng(22.6273, 120.3014)
    private val config = PatrolConfig(speedMps = 20.0 / 3.6, speedJitterPct = 0.0, orbitAtWaypoints = true)
    private val a = Waypoint("a", "A", 22.6300, 120.3014, radiusM = 30.0, dwellSec = 60)
    private val b = Waypoint("b", "B", 22.6300, 120.3060, radiusM = 30.0, dwellSec = 60)

    private fun lap(vararg wps: Waypoint) = PatrolPlanner.planLap(home, wps.toList(), config, wps.indices.toList())

    @Test
    fun speedChangeAppliesOnTheNextTick() {
        val sim = WalkSimulator(config, Random(3))
        sim.load(lap(a))
        val before = sim.advance(1.0).distanceDeltaM
        assertEquals(20.0 / 3.6, before, 0.01)
        sim.updateConfig(config.copy(speedMps = 4.7 / 3.6))
        val after = sim.advance(1.0).distanceDeltaM
        assertEquals(4.7 / 3.6, after, 0.01)
    }

    @Test
    fun vehicleOverrideMovesAtVehicleSpeedAndCountsNoSteps() {
        val sim = WalkSimulator(config, Random(3))
        sim.load(lap(a))
        sim.travelOverride = TravelMode.CAR
        val s = sim.advance(1.0)
        assertEquals(TravelMode.CAR.speedMps, s.distanceDeltaM, 0.01)
        assertEquals(TravelMode.CAR.speedMps, s.speedMps, 0.01)
        assertFalse("a car must not produce steps", s.countsSteps)
        // Back to walking: configured speed, steps again.
        sim.travelOverride = null
        val w = sim.advance(1.0)
        assertEquals(20.0 / 3.6, w.distanceDeltaM, 0.01)
        assertTrue(w.countsSteps)
    }

    @Test
    fun explicitLegModeBeatsTheOverride() {
        // A decor trip's highway leg keeps its own speed whatever the live override says.
        val far = LatLng(22.7000, 120.3014)
        val trip = PatrolPlanner.planTripTo(home, far, config, TravelMode.HIGHWAY, wanderRadiusM = 30.0, wanderSec = 60)
        val sim = WalkSimulator(config, Random(1))
        sim.load(trip)
        sim.travelOverride = TravelMode.BIKE
        val s = sim.advance(1.0)
        assertEquals(TravelMode.HIGHWAY.speedMps, s.distanceDeltaM, 0.01)
    }

    @Test
    fun joystickWalksAlongTheBearingAndStandsStillAtZero() {
        val sim = WalkSimulator(config, Random(5))
        sim.load(lap(a))
        sim.advance(1.0)
        val start = sim.current().position
        repeat(10) { sim.advance(0.0) }
        // Due east for ten seconds at full deflection.
        var moved = 0.0
        repeat(10) { moved += sim.advanceManual(1.0, 90.0, 1.0).distanceDeltaM }
        val here = sim.current().position
        assertEquals(10 * 20.0 / 3.6, moved, 0.01)
        assertEquals(90.0, GeoMath.bearingDeg(start, here), 3.0)
        assertEquals(moved, GeoMath.distanceM(start, here), 2.5)   // lateral noise <= ~1 m each end
        // Knob released: no movement, zero speed, position held.
        val still = sim.advanceManual(1.0, 45.0, 0.0)
        assertEquals(0.0, still.distanceDeltaM, 0.0)
        assertEquals(0.0, still.speedMps, 0.0)
        assertTrue(GeoMath.distanceM(here, still.position) < 2.5)
        // Half deflection = half speed.
        assertEquals(0.5 * 20.0 / 3.6, sim.advanceManual(1.0, 0.0, 0.5).distanceDeltaM, 0.01)
    }

    @Test
    fun joystickStepsFollowTheOverride() {
        val sim = WalkSimulator(config, Random(5))
        sim.load(lap(a))
        assertTrue(sim.advanceManual(1.0, 0.0, 1.0).countsSteps)
        sim.travelOverride = TravelMode.PLANE
        val s = sim.advanceManual(1.0, 0.0, 1.0)
        assertFalse(s.countsSteps)
        assertEquals(TravelMode.PLANE.speedMps, s.distanceDeltaM, 0.01)
    }

    @Test
    fun loadingAPlanAfterTheJoystickStartsFromWhereItLeftUs() {
        val sim = WalkSimulator(config, Random(5))
        sim.load(lap(a))
        repeat(5) { sim.advanceManual(1.0, 90.0, 1.0) }
        val here = sim.current().position
        val next = PatrolPlanner.planLap(here, listOf(a), config, listOf(0))
        sim.load(next)
        assertTrue(GeoMath.distanceM(here, sim.current().position) < 0.01)
        val s = sim.advance(1.0)
        assertEquals(20.0 / 3.6, s.distanceDeltaM, 0.01)
        assertEquals(SegmentKind.TRAVEL, s.kind)
    }

    @Test
    fun remainingLegsKeepTheOrbitInProgress() {
        val plan = lap(a, b)
        val sim = WalkSimulator(config, Random(2))
        sim.load(plan)
        // Walk until we are inside A's orbit.
        var s = sim.advance(1.0)
        var guard = 0
        while (s.kind != SegmentKind.ORBIT && guard++ < 10_000) s = sim.advance(1.0)
        assertEquals(SegmentKind.ORBIT, s.kind)
        assertEquals(0, s.waypointIndex)

        val legs = sim.remainingLegsOfCurrentWaypoint()
        assertTrue(legs.isNotEmpty())
        assertTrue("every kept leg belongs to A", legs.all { it.waypointIndex == 0 })
        assertTrue("first kept leg starts where we are", GeoMath.distanceM(legs.first().from, sim.current().position) < 2.5)
        // The kept legs are exactly the rest of A's orbit: nothing of B in them, and their length
        // matches what the original plan still had for A.
        val originalRestOfA = plan.segments.drop(s.segmentIndex + 1).takeWhile { it.waypointIndex == 0 }.sumOf { it.lengthM }
        val keptTail = legs.drop(1).sumOf { it.lengthM }
        assertEquals(originalRestOfA, keptTail, 0.01)

        // A re-plan built on them continues seamlessly and B is still visited after A.
        val merged = PatrolPlan.of(legs + PatrolPlanner.planLap(legs.last().to, listOf(a, b), config, listOf(1)).segments)
        sim.load(merged)
        var arrivedB = false
        guard = 0
        while (!sim.finished && guard++ < 10_000) { if (sim.advance(1.0).arrivedAtWaypoint == 1) arrivedB = true }
        assertTrue(arrivedB)
    }

    @Test
    fun remainingLegsAreEmptyWhenFinishedOrManual() {
        val sim = WalkSimulator(config, Random(2))
        assertTrue(sim.remainingLegsOfCurrentWaypoint().isEmpty())
        sim.load(lap(a))
        sim.advanceManual(1.0, 0.0, 1.0)
        assertTrue("joystick in control: nothing to keep", sim.remainingLegsOfCurrentWaypoint().isEmpty())
    }
}
