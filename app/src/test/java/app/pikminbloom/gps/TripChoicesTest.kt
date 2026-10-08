package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.ui.TripChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前往後停在這裡 over a long way asks how to get there (2026-10-08). */
class TripChoicesTest {
    private val cfg = PatrolConfig(speedMps = 18.0 / 3.6, strideM = 0.70)

    @Test
    fun asksOnlyOnFootAndBeyondTheThreshold() {
        assertTrue("62 km on foot", TripChoices.shouldAsk(62_000.0, 15.0, vehicleInForce = null))
        assertFalse("10 km is short", TripChoices.shouldAsk(10_000.0, 15.0, vehicleInForce = null))
        assertFalse("a vehicle already writes no steps", TripChoices.shouldAsk(62_000.0, 15.0, vehicleInForce = TravelMode.CAR))
        assertTrue("a walking pace is still on foot", TripChoices.shouldAsk(62_000.0, 15.0, vehicleInForce = TravelMode.RUN))
        assertFalse("0 = never ask", TripChoices.shouldAsk(620_000.0, 0.0, vehicleInForce = null))
        assertFalse("unknown distance", TripChoices.shouldAsk(null, 15.0, vehicleInForce = null))
    }

    @Test
    fun walkingFirstWithItsSteps_thenTheVehiclesWithout() {
        val list = TripChoices.build(62_000.0, cfg, injectSteps = true)
        assertEquals(listOf(null, TravelMode.CAR, TravelMode.HIGHWAY, TravelMode.PLANE), list.map { it.mode })
        assertEquals(88_571L, list[0].steps)                       // 62 km / 0.7 m
        assertTrue(list.drop(1).all { it.steps == null })
        assertEquals(12_400L, list[0].etaSec)                      // 62 km at 18 km/h = 3 h 26 min 40 s
    }

    @Test
    fun labelsSayHowLongAndWhetherStepsAreWritten() {
        val list = TripChoices.build(62_000.0, cfg, injectSteps = true)
        assertEquals("走路 18 km/h — 約 3 小時 27 分，寫入約 8.9 萬步", TripChoices.label(list[0], injectSteps = true))
        assertEquals("汽車 90 km/h — 約 42 分，不寫步數", TripChoices.label(list[2], injectSteps = true))
        assertEquals("到「淺草寺」約 62 km，要怎麼過去？", TripChoices.title("淺草寺", 62_000.0))
    }

    @Test
    fun withStepWritingOffTheWalkSaysSo() {
        val walk = TripChoices.build(62_000.0, cfg, injectSteps = false)[0]
        assertNull(walk.steps)
        assertTrue(TripChoices.label(walk, injectSteps = false).endsWith("不寫步數（寫入步數已關閉）"))
    }

    @Test
    fun theVehicleSpeedsFollowSettings() {
        val fast = cfg.copy(vehicleSpeedsKmh = mapOf(TravelMode.PLANE to 1200.0))
        val plane = TripChoices.build(62_000.0, fast, injectSteps = true).last()
        assertEquals(1200.0, plane.kmh, 0.0)
        assertEquals("約 4 分", TripChoices.etaText(plane.etaSec))
    }

    @Test
    fun etaAndStepTexts() {
        assertEquals("不到 1 分鐘", TripChoices.etaText(0))
        assertEquals("約 1 分", TripChoices.etaText(30))
        assertEquals("約 1 小時 0 分", TripChoices.etaText(3_600))
        assertEquals("8,571 步", TripChoices.stepsText(8_571))
        assertEquals("8.9 萬步", TripChoices.stepsText(88_571))
    }
}
