package app.pikminbloom.gps

import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.ui.SpeedStepper
import org.junit.Assert.assertEquals
import org.junit.Test

/** The floating bar's 速度 －／＋ (asked for 2026-10-03). */
class SpeedStepperTest {
    @Test
    fun walkingStepsByOneKmhOntoWholeNumbers() {
        assertEquals(5.0, SpeedStepper.next(null, 4.7, up = true), 0.0)
        assertEquals(4.0, SpeedStepper.next(null, 4.7, up = false), 0.0)
        assertEquals(19.0, SpeedStepper.next(null, 18.0, up = true), 0.0)
    }

    @Test
    fun walkingNeverGoesAboveTwentyKmh() {
        assertEquals(20.0, SpeedStepper.next(null, 19.5, up = true), 0.0)
        assertEquals(20.0, SpeedStepper.next(null, 20.0, up = true), 0.0)
    }

    @Test
    fun theWalkingCapIsTheAppWideOne() {
        assertEquals(Prefs.MAX_SPEED_MPS * 3.6, SpeedStepper.WALK_MAX_KMH, 1e-9)
    }

    @Test
    fun walkingNeverGoesBelowTwoKmh() {
        assertEquals(2.0, SpeedStepper.next(null, 2.0, up = false), 0.0)
    }

    @Test
    fun minusNeverRaisesAWalkThatIsAtOrBelowTheFloor() {
        // Settings has no range check and Prefs.config() only clamps at 0.3 m/s, so ~1.08-2 km/h can be stored.
        assertEquals(1.5, SpeedStepper.next(null, 1.5, up = false), 0.0)
        assertEquals(2.0, SpeedStepper.next(null, 2.0, up = false), 0.0)
    }

    @Test
    fun plusFromBelowTheFloorStillGoesUp() {
        assertEquals(2.0, SpeedStepper.next(null, 1.5, up = true), 0.0)
    }

    @Test
    fun walkingSpeedReadBackFromMetresPerSecondStillStepsOffTheWholeNumberItWas() {
        // Prefs keeps the walking speed as m/s and OverlayService reads it back as speedMps * 3.6, so 15 km/h
        // arrives as 15.000000000000002: without the rounding tolerance "-" would round back to 15 and do nothing.
        val fifteenAfterTheRoundTrip = 15.0 / 3.6 * 3.6
        assertEquals("a hair over 15 km/h, minus", 14.0, SpeedStepper.next(null, fifteenAfterTheRoundTrip, up = false), 0.0)
        assertEquals("a hair over 15 km/h, plus", 16.0, SpeedStepper.next(null, fifteenAfterTheRoundTrip, up = true), 0.0)
    }

    @Test
    fun aSpeedAHairUnderAWholeNumberCountsAsThatNumber() {
        // The mirror image (no whole km/h up to 20 drifts this way, so the value is synthetic): "+" must reach 16, not 15 again.
        assertEquals("a hair under 15 km/h, plus", 16.0, SpeedStepper.next(null, Math.nextDown(15.0), up = true), 0.0)
    }

    @Test
    fun eachVehicleStepsByItsOwnIncrement() {
        assertEquals(19.0, SpeedStepper.next(TravelMode.BIKE, 18.0, up = true), 0.0)
        assertEquals(50.0, SpeedStepper.next(TravelMode.CAR, 45.0, up = true), 0.0)
        assertEquals(40.0, SpeedStepper.next(TravelMode.CAR, 42.0, up = false), 0.0)
        assertEquals(100.0, SpeedStepper.next(TravelMode.HIGHWAY, 90.0, up = true), 0.0)
        assertEquals(650.0, SpeedStepper.next(TravelMode.PLANE, 600.0, up = true), 0.0)
    }

    @Test
    fun vehiclesStayInsideTheRangeSettingsAccepts() {
        assertEquals(Prefs.MIN_VEHICLE_KMH, SpeedStepper.next(TravelMode.CAR, 5.0, up = false), 0.0)
        assertEquals(Prefs.MAX_VEHICLE_KMH, SpeedStepper.next(TravelMode.PLANE, 1200.0, up = true), 0.0)
    }

    @Test
    fun theBikeIsAVehicleSoItKeepsToTheVehicleRangeNotTheWalkingOne() {
        // BIKE steps by 1 like walking, but countsSteps is false: its bounds are Settings' 5-1200 km/h, not 2-20.
        assertEquals("bike at the vehicle minimum, minus", Prefs.MIN_VEHICLE_KMH, SpeedStepper.next(TravelMode.BIKE, Prefs.MIN_VEHICLE_KMH, up = false), 0.0)
        assertEquals("bike at the vehicle maximum, plus", Prefs.MAX_VEHICLE_KMH, SpeedStepper.next(TravelMode.BIKE, Prefs.MAX_VEHICLE_KMH, up = true), 0.0)
    }

    @Test
    fun storedTextLooksLikeWhatTheSettingsScreenStores() {
        assertEquals("19", SpeedStepper.text(19.0))
        assertEquals("17.5", SpeedStepper.text(17.5))
    }
}
