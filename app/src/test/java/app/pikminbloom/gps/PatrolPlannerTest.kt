package app.pikminbloom.gps

import app.pikminbloom.gps.data.LoopMode
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.route.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatrolPlannerTest {

    private val home = LatLng(25.0330, 121.5654)
    private val config = PatrolConfig(speedMps = 1.3)
    private val flowerA = Waypoint("a", "A", 25.0348, 121.5654, radiusM = 30.0, dwellSec = 120)   // ~200 m north
    private val flowerB = Waypoint("b", "B", 25.0348, 121.5680, radiusM = 30.0, dwellSec = 0)     // pass-through

    @Test
    fun orderForModes() {
        assertEquals(listOf(0, 1, 2), PatrolPlanner.orderFor(0, 3, LoopMode.LOOP))
        assertEquals(listOf(0, 1, 2), PatrolPlanner.orderFor(5, 3, LoopMode.LOOP))
        assertEquals(listOf(0, 1, 2), PatrolPlanner.orderFor(0, 3, LoopMode.ONCE))
        assertEquals(emptyList<Int>(), PatrolPlanner.orderFor(1, 3, LoopMode.ONCE))
        assertEquals(listOf(0, 1, 2), PatrolPlanner.orderFor(0, 3, LoopMode.PINGPONG))
        assertEquals(listOf(1, 0), PatrolPlanner.orderFor(1, 3, LoopMode.PINGPONG))
        assertEquals(listOf(1, 2), PatrolPlanner.orderFor(2, 3, LoopMode.PINGPONG))
        assertEquals(listOf(0), PatrolPlanner.orderFor(7, 1, LoopMode.PINGPONG))
        assertEquals(emptyList<Int>(), PatrolPlanner.orderFor(0, 0, LoopMode.LOOP))
    }

    @Test
    fun lapHasTravelThenOrbitInsideCircle() {
        val plan = PatrolPlanner.planLap(home, listOf(flowerA), config, listOf(0))
        assertTrue(plan.segments.isNotEmpty())
        val first = plan.segments.first()
        assertEquals(SegmentKind.TRAVEL, first.kind)
        assertTrue(first.arrivalAtEnd)
        assertEquals(home, first.from)

        val orbit = plan.segments.filter { it.kind == SegmentKind.ORBIT }
        assertTrue("expected orbit segments", orbit.isNotEmpty())
        val center = flowerA.latLng
        for (s in orbit) {
            assertTrue("orbit point outside circle: ${GeoMath.distanceM(center, s.from)}", GeoMath.distanceM(center, s.from) <= 30.5)
            assertTrue("orbit point outside circle: ${GeoMath.distanceM(center, s.to)}", GeoMath.distanceM(center, s.to) <= 30.5)
            assertTrue("orbit segment too short: ${s.lengthM}", s.lengthM >= PatrolPlanner.MIN_SEGMENT_M - 1e-6)
        }
        val orbitLength = orbit.sumOf { it.lengthM }
        val target = 120 * 1.3
        assertTrue("orbit length $orbitLength < target $target", orbitLength >= target)
        assertTrue("orbit length $orbitLength unreasonably long", orbitLength < target + 400)
        // The sweep must visit many distinct 5 m cells, not a tight circle: sample every 2 m along the path.
        val cells = HashSet<Pair<Long, Long>>()
        for (s in orbit) {
            var d = 0.0
            while (d <= s.lengthM) {
                val p = GeoMath.interpolate(s.from, s.to, d / s.lengthM)
                val north = GeoMath.distanceM(center, LatLng(p.lat, center.lon)) * Math.signum(p.lat - center.lat)
                val east = GeoMath.distanceM(center, LatLng(center.lat, p.lon)) * Math.signum(p.lon - center.lon)
                cells.add(Pair(Math.floor(north / 5.0).toLong(), Math.floor(east / 5.0).toLong()))
                d += 2.0
            }
        }
        // ~156 m of path at 7 m line spacing should cross well over 30 cells of a 30 m circle (~113 cells total).
        assertTrue("only ${cells.size} distinct cells", cells.size >= 30)
        // Only one arrival flag per waypoint.
        assertEquals(1, plan.segments.count { it.arrivalAtEnd })
    }

    @Test
    fun passThroughWaypointWalksToEdge() {
        val plan = PatrolPlanner.planLap(home, listOf(flowerB), config, listOf(0))
        assertEquals(1, plan.segments.size)
        val s = plan.segments[0]
        assertEquals(SegmentKind.TRAVEL, s.kind)
        assertTrue(s.arrivalAtEnd)
        assertEquals(30.0, GeoMath.distanceM(flowerB.latLng, s.to), 0.1)
    }

    @Test
    fun multiWaypointLapChainsFromLastOrbitPoint() {
        val plan = PatrolPlanner.planLap(home, listOf(flowerA, flowerB), config, listOf(0, 1))
        val travelToB = plan.segments.last()
        assertEquals(1, travelToB.waypointIndex)
        assertEquals(SegmentKind.TRAVEL, travelToB.kind)
        val lastOrbitOfA = plan.segments.last { it.kind == SegmentKind.ORBIT && it.waypointIndex == 0 }
        assertEquals(lastOrbitOfA.to, travelToB.from)
        assertEquals(2, plan.segments.count { it.arrivalAtEnd })
        assertEquals(plan.segments.sumOf { it.lengthM }, plan.totalLengthM, 1e-6)
    }

    @Test
    fun differentLapsRotateTheSweep() {
        val lap0 = PatrolPlanner.planLap(home, listOf(flowerA), config, listOf(0), lap = 0)
        val lap1 = PatrolPlanner.planLap(home, listOf(flowerA), config, listOf(0), lap = 1)
        val b0 = lap0.segments.first { it.kind == SegmentKind.ORBIT }.bearingDeg
        val b1 = lap1.segments.first { it.kind == SegmentKind.ORBIT }.bearingDeg
        assertTrue("sweep orientation should change between laps", Math.abs(b0 - b1) > 5.0)
    }

    @Test
    fun returnHomeIsSingleTravelSegment() {
        val plan = PatrolPlanner.planReturnHome(flowerA.latLng, home)
        assertEquals(1, plan.segments.size)
        assertEquals(null, plan.segments[0].waypointIndex)
        assertEquals(home, plan.segments[0].to)
    }
}
