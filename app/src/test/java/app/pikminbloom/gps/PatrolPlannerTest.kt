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
    private val config = PatrolConfig(speedMps = 1.3, orbitAtWaypoints = true)
    // radiusM / dwellSec are only what an old waypoints.json can still carry: a visit ignores them
    // (PatrolPlanner.CIRCLE_RADIUS_M / ORBIT_DWELL_SEC), so B's dwell of 0 is no longer a pass-through.
    private val flowerA = Waypoint("a", "A", 25.0348, 121.5654, radiusM = 30.0, dwellSec = 120)   // ~200 m north
    private val flowerB = Waypoint("b", "B", 25.0348, 121.5680, radiusM = 30.0, dwellSec = 0)     // ~260 m east of A

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
    fun orbitDisabledJustPassesThroughEveryFlower() {
        val off = config.copy(orbitAtWaypoints = false)
        val plan = PatrolPlanner.planLap(home, listOf(flowerA, flowerB), off, listOf(0, 1))
        assertTrue("no orbit expected", plan.segments.none { it.kind == SegmentKind.ORBIT })
        assertEquals(2, plan.segments.size)
        assertEquals(2, plan.segments.count { it.arrivalAtEnd })
        // Each leg ends on the flower's circle edge, so the game still counts us as "at" the flower.
        assertEquals(30.0, GeoMath.distanceM(flowerA.latLng, plan.segments[0].to), 0.1)
        assertEquals(30.0, GeoMath.distanceM(flowerB.latLng, plan.segments[1].to), 0.1)
    }

    @Test
    fun passThroughWaypointWalksToEdge() {
        val plan = PatrolPlanner.planLap(home, listOf(flowerB), config.copy(orbitAtWaypoints = false), listOf(0))
        assertEquals(1, plan.segments.size)
        val s = plan.segments[0]
        assertEquals(SegmentKind.TRAVEL, s.kind)
        assertTrue(s.arrivalAtEnd)
        assertEquals(30.0, GeoMath.distanceM(flowerB.latLng, s.to), 0.1)
    }

    @Test
    fun multiWaypointLapChainsFromLastOrbitPoint() {
        val plan = PatrolPlanner.planLap(home, listOf(flowerA, flowerB), config, listOf(0, 1))
        val travelToB = plan.segments.first { it.waypointIndex == 1 }
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

    @Test
    fun teleportEntryLandsJustOutsideTheCircleOnTheNearSide() {
        val wp = Waypoint("a", "A", 22.7583, 121.1379, radiusM = 30.0, dwellSec = 0)
        val from = GeoMath.offsetMeters(wp.latLng, 0.0, 500.0)          // 500 m east
        val at = PatrolPlanner.teleportEntry(wp, from)
        assertEquals(35.0, GeoMath.distanceM(wp.latLng, at), 0.5)        // radius + 5 m
        assertEquals(90.0, GeoMath.bearingDeg(wp.latLng, at), 1.0)       // on the side we came from
    }

    @Test
    fun teleportEntryFromTheFlowerItselfStillLeavesTheCircle() {
        val wp = Waypoint("a", "A", 22.7583, 121.1379, radiusM = 30.0, dwellSec = 0)
        val at = PatrolPlanner.teleportEntry(wp, wp.latLng)
        assertEquals(35.0, GeoMath.distanceM(wp.latLng, at), 0.5)
    }

    /** Values only an old waypoints.json can still carry (a 10 m circle, no dwell); flowers no longer have their own. */
    private val legacy = Waypoint("l", "L", 25.0348, 121.5654, radiusM = 10.0, dwellSec = 0)

    @Test
    fun aVisitEndsOnTheThirtyMetreLineWhateverTheStoredRadius() {
        val plan = PatrolPlanner.planLap(home, listOf(legacy), config.copy(orbitAtWaypoints = false), listOf(0))
        assertEquals(30.0, GeoMath.distanceM(legacy.latLng, plan.segments.single().to), 0.1)
    }

    @Test
    fun orbitingLastsTwoMinutesWhateverTheStoredDwell() {
        val plan = PatrolPlanner.planLap(home, listOf(legacy), config, listOf(0))
        val orbitM = plan.segments.filter { it.kind == SegmentKind.ORBIT }.sumOf { it.lengthM }
        val target = 120 * 1.3
        // The sweep stops at the first chord past the target: at most one chord (53 m in a 30 m circle) plus its connector.
        assertTrue("orbit $orbitM m for a $target m target", orbitM >= target && orbitM < target + 60)
    }

    @Test
    fun theOrbitDwellIsTheOldDefaultOfTwoMinutes() {
        assertEquals(120, PatrolPlanner.ORBIT_DWELL_SEC)
    }

    @Test
    fun aTeleportLandsOutsideTheThirtyMetreCircleWhateverTheStoredRadius() {
        val at = PatrolPlanner.teleportEntry(legacy, GeoMath.offsetMeters(legacy.latLng, 0.0, 500.0))
        assertEquals(35.0, GeoMath.distanceM(legacy.latLng, at), 0.5)
    }
}
