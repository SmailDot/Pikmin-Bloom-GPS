package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.route.SegmentKind
import app.pikminbloom.gps.sim.WalkSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WalkSimulatorTest {

    private val home = LatLng(25.0330, 121.5654)
    private val config = PatrolConfig(speedMps = 1.3, orbitAtWaypoints = true)
    private val flower = Waypoint("a", "A", 25.0348, 121.5654, radiusM = 30.0, dwellSec = 60)

    private fun plan() = PatrolPlanner.planLap(home, listOf(flower), config, listOf(0))

    @Test
    fun sixtySecondsCoverAboutSeventyEightMetres() {
        val sim = WalkSimulator(config, Random(1))
        sim.load(plan())
        var moved = 0.0
        repeat(60) { moved += sim.advance(1.0).distanceDeltaM }
        assertEquals("moved", 78.0, moved, 1e-6)   // 60 s at exactly 1.3 m/s
        val reported = sim.current().position
        val exactDistanceFromHome = GeoMath.distanceM(home, reported)
        assertTrue("reported position drifted too far: $exactDistanceFromHome vs $moved", Math.abs(exactDistanceFromHome - moved) < 2.0)
    }

    @Test
    fun distanceIsConservedAcrossSegmentsAndArrivalFiresOnce() {
        val p = plan()
        val sim = WalkSimulator(config, Random(7))
        sim.load(p)
        var total = 0.0
        var arrivals = 0
        var lapFinished = 0
        var ticks = 0
        while (!sim.finished && ticks < 10_000) {
            val s = sim.advance(1.0)
            total += s.distanceDeltaM
            if (s.arrivedAtWaypoint == 0) arrivals++
            if (s.lapFinished) lapFinished++
            // The speed is the configured one, exactly (the jitter wobble was retired 2026-10-03); the finishing tick reports 0.
            if (!s.lapFinished) assertEquals("speed at tick $ticks", 1.3, s.speedMps, 1e-9)
            assertTrue(s.accuracyM in WalkSimulator.ACCURACY_MIN_M..WalkSimulator.ACCURACY_MAX_M)
            ticks++
        }
        assertTrue(sim.finished)
        assertEquals(p.totalLengthM, total, 0.01)
        assertEquals(1, arrivals)
        assertEquals(1, lapFinished)
        // After finishing we stay put with zero speed.
        val after = sim.advance(1.0)
        assertEquals(0.0, after.speedMps, 0.0)
        assertEquals(0.0, after.distanceDeltaM, 0.0)
        assertFalse(after.lapFinished)
        assertEquals(0.0, GeoMath.distanceM(p.end!!, after.position), 1.6)
    }

    @Test
    fun deterministicForSeed() {
        val a = WalkSimulator(config, Random(42)).also { it.load(plan()) }
        val b = WalkSimulator(config, Random(42)).also { it.load(plan()) }
        repeat(30) { assertEquals(a.advance(1.0), b.advance(1.0)) }
    }

    @Test
    fun currentDoesNotMove() {
        val sim = WalkSimulator(config, Random(3)).also { it.load(plan()) }
        sim.advance(5.0)
        val c1 = sim.current()
        val c2 = sim.current()
        assertEquals(c1.position, c2.position)
        assertEquals(0.0, c1.speedMps, 0.0)
        assertEquals(0.0, c1.distanceDeltaM, 0.0)
    }

    @Test
    fun emptyPlanIsFinishedImmediately() {
        val sim = WalkSimulator(config, Random(0))
        sim.load(PatrolPlanner.planLap(home, emptyList(), config, emptyList()))
        assertTrue(sim.finished)
        val s = sim.advance(1.0)
        assertEquals(0.0, s.distanceDeltaM, 0.0)
    }

    @Test
    fun walkingSpeedIsExactlyTheConfiguredSpeedEveryTick() {
        val sim = WalkSimulator(config, Random(9))
        sim.load(plan())
        var ticks = 0
        var orbitTicks = 0
        while (!sim.finished && ticks < 10_000) {
            val s = sim.advance(1.0)
            // The finishing tick may be short and reports 0 m/s; every other tick is exactly the configured speed.
            if (!s.lapFinished) {
                assertEquals("distance at tick $ticks", 1.3, s.distanceDeltaM, 1e-9)
                assertEquals("speed at tick $ticks", 1.3, s.speedMps, 1e-9)
            }
            if (s.kind == SegmentKind.ORBIT) orbitTicks++
            ticks++
        }
        assertTrue("the lap did not finish within $ticks ticks", sim.finished)
        assertTrue("no tick was spent on an orbit leg, so only the first leg was checked", orbitTicks > 0)
    }

    @Test
    fun joystickStepsAreExactlyTheConfiguredSpeedTimesTheDeflection() {
        val sim = WalkSimulator(config, Random(9))
        sim.load(plan())
        val full = sim.advanceManual(1.0, 90.0, 1.0)
        assertEquals("full deflection moves", 1.3, full.distanceDeltaM, 1e-9)
        assertEquals("full deflection reports", 1.3, full.speedMps, 1e-9)
        val half = sim.advanceManual(1.0, 90.0, 0.5)
        assertEquals("half deflection moves", 0.65, half.distanceDeltaM, 1e-9)
        assertEquals("half deflection reports", 0.65, half.speedMps, 1e-9)
    }

    @Test
    fun accuracyAndAltitudeStayInsideTheFixedBands() {
        val sim = WalkSimulator(config, Random(11)).also { it.load(plan()) }
        repeat(300) {
            val s = sim.advance(1.0)
            assertTrue("accuracy ${s.accuracyM}", s.accuracyM in WalkSimulator.ACCURACY_MIN_M..WalkSimulator.ACCURACY_MAX_M)
            assertTrue("altitude ${s.altitudeM}", s.altitudeM in (WalkSimulator.ALTITUDE_M - 1.0)..(WalkSimulator.ALTITUDE_M + 1.0))
        }
    }

    @Test
    fun theFixedBandsAreTheOldDefaults() {
        assertEquals(3f, WalkSimulator.ACCURACY_MIN_M, 0f)
        assertEquals(9f, WalkSimulator.ACCURACY_MAX_M, 0f)
        assertEquals(20.0, WalkSimulator.ALTITUDE_M, 0.0)
    }

    // A teleport 回家 only pushes the destination to the game, so PatrolService.tick() seats the simulator
    // there with loadPlan(planReturnHome(h, h)) - the call park() makes - when the jump is pushed. Anything
    // planned from sim.current() afterwards (a walk 回家, 立刻前往, the joystick) then starts at the landing,
    // not at the spot jumped from. PatrolService has no JVM test, so these pin the building block it relies
    // on; they pass on the simulator as it is (nothing here needed a code change).

    /** 20 km from [home], where the avatar was walking: a teleport lands here. */
    private val landing = GeoMath.offsetMeters(home, 20_000.0, 0.0)

    private fun walkingFromHomeTowardsTheLanding(): WalkSimulator =
        WalkSimulator(config, Random(5)).also { sim ->
            sim.load(PatrolPlanner.planReturnHome(home, landing))
            repeat(10) { sim.advance(1.0) }
        }

    @Test
    fun aZeroLengthReturnPlanSeatsTheSimulatorAtItsPointWhereverItWas() {
        val sim = walkingFromHomeTowardsTheLanding()
        assertTrue("setup: still near home, far from the landing", GeoMath.distanceM(landing, sim.current().position) > 19_000.0)

        sim.load(PatrolPlanner.planReturnHome(landing, landing))

        assertEquals(landing, sim.current().position)
    }

    @Test
    fun aSeatedSimulatorIsFinishedAfterOneTickWithoutMovingAway() {
        val sim = walkingFromHomeTowardsTheLanding()
        sim.load(PatrolPlanner.planReturnHome(landing, landing))

        val s = sim.advance(1.0)

        assertEquals("a zero-length plan covers no distance", 0.0, s.distanceDeltaM, 0.0)
        assertTrue("the zero-length plan is over after one tick", sim.finished && s.lapFinished)
        assertEquals("position drifts by lateral noise only", 0.0, GeoMath.distanceM(landing, s.position), 1.6)
    }

    @Test
    fun aWalkPlannedFromTheSeatedSimulatorStartsAtTheLandingNotWhereTheJumpStarted() {
        val sim = walkingFromHomeTowardsTheLanding()
        val jumpedFrom = sim.current().position
        sim.load(PatrolPlanner.planReturnHome(landing, landing))

        val walk = PatrolPlanner.planReturnHome(sim.current().position, GeoMath.offsetMeters(landing, 0.0, 500.0))
        sim.load(walk)
        val first = sim.advance(1.0)

        assertEquals(landing, walk.start)
        assertEquals("first step of the walk, from the landing", 0.0, GeoMath.distanceM(landing, first.position), 3.0)
        assertTrue("the walk must not restart at the spot jumped from", GeoMath.distanceM(jumpedFrom, first.position) > 19_000.0)
    }
}
