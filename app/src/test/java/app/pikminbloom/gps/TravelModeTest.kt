package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
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

class TravelModeTest {

    private val here = LatLng(22.7583, 120.3379)
    private val config = PatrolConfig(speedMps = 1.3, speedJitterPct = 0.0)

    @Test
    fun onlyHumanPacedModesCountSteps() {
        for (m in TravelMode.entries) {
            val expected = m in setOf(TravelMode.WALK, TravelMode.BRISK, TravelMode.RUN)
            assertEquals("${m.name} countsSteps", expected, m.countsSteps)
        }
        // Everything that does not count steps is also above the game's planting cutoff, so those
        // legs plant nothing either - which is the honest outcome for a vehicle.
        for (m in TravelMode.entries.filterNot { it.countsSteps }) {
            assertTrue("${m.name} should be faster than walking pace", m.speedKmh > 15.0)
        }
    }

    @Test
    fun suggestedModeScalesWithDistance() {
        assertEquals(TravelMode.WALK, TravelMode.suggestFor(500.0))
        assertEquals(TravelMode.CAR, TravelMode.suggestFor(5_000.0))
        assertEquals(TravelMode.HIGHWAY, TravelMode.suggestFor(80_000.0))
        assertEquals(TravelMode.PLANE, TravelMode.suggestFor(1_500_000.0))
    }

    @Test
    fun tripPlanTravelsByVehicleThenWandersOnFoot() {
        val far = GeoMath.offsetMeters(here, 20_000.0, 0.0)
        val plan = PatrolPlanner.planTripTo(here, far, config, TravelMode.HIGHWAY, wanderRadiusM = 60.0, wanderSec = 300)

        val travel = plan.segments.first()
        assertEquals(SegmentKind.TRAVEL, travel.kind)
        assertEquals(TravelMode.HIGHWAY, travel.travelMode)
        assertTrue("should arrive at the destination", travel.arrivalAtEnd)

        val wander = plan.segments.drop(1)
        assertTrue("expected a wander around the destination", wander.isNotEmpty())
        assertTrue("wander must be on foot", wander.all { it.travelMode == null })
        assertTrue("wander must stay inside the radius", wander.all { GeoMath.distanceM(far, it.to) <= 61.0 })
        assertTrue("wander should cover roughly the requested time", wander.sumOf { it.lengthM } >= 300 * 1.3 * 0.9)
    }

    @Test
    fun vehicleLegMovesFastAndProducesNoSteps() {
        val far = GeoMath.offsetMeters(here, 20_000.0, 0.0)
        val plan = PatrolPlanner.planTripTo(here, far, config, TravelMode.HIGHWAY, wanderSec = 60)
        val sim = WalkSimulator(config, Random(5))
        sim.load(plan)

        val s = sim.advance(1.0)
        assertFalse("a highway leg must not count steps", s.countsSteps)
        // 90 km/h is 25 m/s, not the 1.3 m/s configured walking speed.
        assertEquals(25.0, s.distanceDeltaM, 1.0)

        // Once the vehicle leg is done the walk resumes and steps count again. The crossing tick
        // itself legitimately mixes both speeds (drive the last metres, then walk the rest of the
        // second), so the pure walking pace is only guaranteed from the tick after it.
        var guard = 0
        var crossing = s
        while (crossing.kind != SegmentKind.ORBIT && guard++ < 5_000) crossing = sim.advance(1.0)
        assertTrue("never reached the wander", guard < 5_000)
        assertTrue("wander must count steps", crossing.countsSteps)
        assertTrue("the crossing tick should be slower than a full highway second", crossing.distanceDeltaM < 25.0)

        val walking = sim.advance(1.0)
        assertTrue(walking.countsSteps)
        assertEquals("should now be walking, not driving", 1.3, walking.distanceDeltaM, 0.2)
    }

    @Test
    fun returnHomeCanUseAVehicleToo() {
        val far = GeoMath.offsetMeters(here, 30_000.0, 0.0)
        val plan = PatrolPlanner.planReturnHome(far, here, TravelMode.HIGHWAY)
        assertEquals(1, plan.segments.size)
        assertEquals(TravelMode.HIGHWAY, plan.segments[0].travelMode)
        assertEquals(here, plan.segments[0].to)
    }

    @Test
    fun walkingPlansStillHaveNoTravelModeSoTheConfiguredSpeedWins() {
        val plan = PatrolPlanner.planReturnHome(GeoMath.offsetMeters(here, 100.0, 0.0), here)
        assertEquals(null, plan.segments[0].travelMode)
        val sim = WalkSimulator(config, Random(1)).also { it.load(plan) }
        val s = sim.advance(1.0)
        assertTrue(s.countsSteps)
        assertEquals(1.3, s.distanceDeltaM, 0.05)
    }
}
