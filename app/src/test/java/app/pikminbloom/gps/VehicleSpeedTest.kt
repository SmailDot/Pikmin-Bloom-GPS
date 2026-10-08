package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.sim.WalkSimulator
import app.pikminbloom.gps.ui.DecorHuntFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Vehicle speeds are settings (2026-09-13); TravelMode only carries the defaults. */
class VehicleSpeedTest {
    private val here = LatLng(22.7583, 121.1379)
    private val base = PatrolConfig(speedMps = 1.3)

    @Test
    fun defaultsMatchTheEnum() {
        for (m in TravelMode.entries) {
            assertEquals(m.name, m.speedKmh, base.speedKmhOf(m), 0.0)
            assertEquals(m.name, m.speedKmh / 3.6, base.speedMpsOf(m), 1e-9)
        }
    }

    @Test
    fun aConfiguredVehicleSpeedOverridesTheDefault() {
        val cfg = base.copy(vehicleSpeedsKmh = mapOf(TravelMode.CAR to 60.0))
        assertEquals(60.0, cfg.speedKmhOf(TravelMode.CAR), 0.0)
        assertEquals(TravelMode.BIKE.speedKmh, cfg.speedKmhOf(TravelMode.BIKE), 0.0)   // untouched
    }

    @Test
    fun aVehicleLegDrivesAtTheConfiguredSpeed() {
        val cfg = base.copy(vehicleSpeedsKmh = mapOf(TravelMode.HIGHWAY to 36.0))   // 10 m/s, not 25
        val far = GeoMath.offsetMeters(here, 20_000.0, 0.0)
        val sim = WalkSimulator(cfg, Random(5))
        sim.load(PatrolPlanner.planTripTo(here, far, cfg, TravelMode.HIGHWAY, wanderSec = 60))
        assertEquals(10.0, sim.advance(1.0).distanceDeltaM, 0.5)
    }

    @Test
    fun theVehicleOverrideUsesTheConfiguredSpeedAndFollowsSettingsChanges() {
        val wp = Waypoint("a", "A", 22.7700, 121.1379, radiusM = 30.0, dwellSec = 0)
        val cfg = base.copy(vehicleSpeedsKmh = mapOf(TravelMode.CAR to 72.0))       // 20 m/s
        val sim = WalkSimulator(cfg, Random(1))
        sim.load(PatrolPlanner.planLap(here, listOf(wp), cfg, listOf(0)))
        sim.travelOverride = TravelMode.CAR
        assertEquals(20.0, sim.advance(1.0).distanceDeltaM, 0.5)

        sim.updateConfig(cfg.copy(vehicleSpeedsKmh = mapOf(TravelMode.CAR to 36.0)))  // settings edit mid-drive
        assertEquals(10.0, sim.advance(1.0).distanceDeltaM, 0.5)
    }

    @Test
    fun labelsShowTheConfiguredSpeed() {
        val cfg = base.copy(vehicleSpeedsKmh = mapOf(TravelMode.PLANE to 850.0, TravelMode.BIKE to 17.5))
        assertEquals("飛機（850 km/h）", DecorHuntFormat.travelModeLabel(TravelMode.PLANE, cfg))
        assertEquals("腳踏車（17.5 km/h）", DecorHuntFormat.travelModeLabel(TravelMode.BIKE, cfg))
    }

    @Test
    fun speedTextKeepsADecimalOnlyWhenTheUserTypedOne() {
        val cfg = base.copy(vehicleSpeedsKmh = mapOf(TravelMode.BIKE to 17.5, TravelMode.CAR to 60.0))
        assertEquals("17.5", cfg.speedTextOf(TravelMode.BIKE))
        assertEquals("60", cfg.speedTextOf(TravelMode.CAR))
        assertEquals("600", cfg.speedTextOf(TravelMode.PLANE))   // default
    }
}
