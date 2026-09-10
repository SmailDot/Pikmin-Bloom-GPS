package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.sim.WalkSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WalkSimulatorTest {

    private val home = LatLng(25.0330, 121.5654)
    private val config = PatrolConfig(speedMps = 1.3, speedJitterPct = 10.0, orbitAtWaypoints = true)
    private val flower = Waypoint("a", "A", 25.0348, 121.5654, radiusM = 30.0, dwellSec = 60)

    private fun plan() = PatrolPlanner.planLap(home, listOf(flower), config, listOf(0))

    @Test
    fun sixtySecondsCoverAboutSeventyEightMetres() {
        val sim = WalkSimulator(config, Random(1))
        sim.load(plan())
        var moved = 0.0
        repeat(60) { moved += sim.advance(1.0).distanceDeltaM }
        assertTrue("moved $moved", moved in 78.0 * 0.85..78.0 * 1.15)
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
            assertTrue(s.speedMps in 1.3 * 0.89..1.3 * 1.11 || s.lapFinished)
            assertTrue(s.accuracyM in config.accuracyMinM..config.accuracyMaxM)
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
}
