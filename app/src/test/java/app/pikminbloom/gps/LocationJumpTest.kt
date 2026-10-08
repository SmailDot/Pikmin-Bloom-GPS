package app.pikminbloom.gps

import app.pikminbloom.gps.data.LocationJump
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** 上次跳躍: informational only, shown for a day (2026-10-03). */
class LocationJumpTest {
    private val taipei = LatLng(25.033964, 121.564468)
    private val min = 60_000L

    @Test
    fun aMoveShorterThanAHundredMetresIsNotAJump() {
        assertNull(LocationJump.of(0L, taipei, GeoMath.offsetMeters(taipei, 99.0, 0.0)))
    }

    @Test
    fun aLongerMoveIsRecordedWithItsDistance() {
        assertEquals(150.0, LocationJump.of(0L, taipei, GeoMath.offsetMeters(taipei, 150.0, 0.0))!!.distanceM, 0.1)
    }

    @Test
    fun aMoveJustOverAHundredMetresIsAJump() {
        // With 99 m above, this pins the line between 99 and 101 m instead of "somewhere under 150".
        val jump = LocationJump.of(0L, taipei, GeoMath.offsetMeters(taipei, 101.0, 0.0))
        assertNotNull("101 m is over the 100 m line", jump)
        assertEquals(101.0, jump!!.distanceM, 0.1)
    }

    @Test
    fun itSurvivesAJsonRoundTrip() {
        val j = LocationJump(atMs = 1_728_000_000_000L, distanceM = 2_104_532.7)
        assertEquals(j, LocationJump.fromJson(j.toJson()))
    }

    @Test
    fun missingOrGarbageJsonIsNoJump() {
        assertNull(LocationJump.fromJson(null))
        assertNull(LocationJump.fromJson("not json"))
    }

    @Test
    fun jsonMissingAFieldIsNoJump() {
        // Never a half-filled jump that would read "上次跳躍 0 m": both fields are required.
        assertNull(LocationJump.fromJson("{}"))
        assertNull(LocationJump.fromJson("""{"atMs":1000}"""))
        assertNull(LocationJump.fromJson("""{"distanceM":850.0}"""))
    }

    @Test
    fun aCrossCountryJumpReadsInWholeKilometresAndMinutes() {
        assertEquals("上次跳躍 1,850 km · 42 分鐘前", LocationJump.readout(LocationJump(0L, 1_850_000.0), 42 * min))
    }

    @Test
    fun aShortJumpReadsInMetresAndHoursAndMinutes() {
        assertEquals("上次跳躍 850 m · 2 小時 5 分鐘前", LocationJump.readout(LocationJump(0L, 850.0), 125 * min))
    }

    @Test
    fun aFewKilometresKeepOneDecimal() {
        assertEquals("上次跳躍 3.4 km · 0 分鐘前", LocationJump.readout(LocationJump(0L, 3_400.0), 0L))
    }

    @Test
    fun aThousandMetresIsTheFirstToReadInKilometres() {
        assertEquals("上次跳躍 999 m · 0 分鐘前", LocationJump.readout(LocationJump(0L, 999.0), 0L))
        assertEquals("上次跳躍 1.0 km · 0 分鐘前", LocationJump.readout(LocationJump(0L, 1_000.0), 0L))
    }

    @Test
    fun tenKilometresDropTheDecimal() {
        assertEquals("上次跳躍 10 km · 0 分鐘前", LocationJump.readout(LocationJump(0L, 10_000.0), 0L))
    }

    @Test
    fun theHourTurnsOverAtSixtyMinutes() {
        assertEquals("上次跳躍 850 m · 59 分鐘前", LocationJump.readout(LocationJump(0L, 850.0), 59 * min))
        assertEquals("上次跳躍 850 m · 1 小時 0 分鐘前", LocationJump.readout(LocationJump(0L, 850.0), 60 * min))
    }

    @Test
    fun theReadoutReadsTheSameInAnyLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)   // the default locale would turn 3.4 into 3,4 and 1,850 into 1.850
        try {
            assertEquals("上次跳躍 3.4 km · 0 分鐘前", LocationJump.readout(LocationJump(0L, 3_400.0), 0L))
            assertEquals("上次跳躍 1,850 km · 0 分鐘前", LocationJump.readout(LocationJump(0L, 1_850_000.0), 0L))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun aClockThatWentBackwardsReadsAsJustNow() {
        assertEquals("上次跳躍 850 m · 0 分鐘前", LocationJump.readout(LocationJump(10 * min, 850.0), 0L))
    }

    @Test
    fun aJumpFromJustUnderADayAgoIsStillShown() {
        assertEquals("上次跳躍 850 m · 23 小時 59 分鐘前", LocationJump.readout(LocationJump(0L, 850.0), (24 * 60 - 1) * min))
    }

    @Test
    fun aJumpJustOverADayOldIsGone() {
        assertNull(LocationJump.readout(LocationJump(0L, 850.0), (24 * 60 + 1) * min))
    }

    @Test
    fun nothingIsShownAfterADay() {
        assertNull(LocationJump.readout(LocationJump(0L, 850.0), 25 * 60 * min))
    }

    @Test
    fun nothingIsShownWithoutAJump() {
        assertNull(LocationJump.readout(null, 0L))
    }

    @Test
    fun theDistanceAloneReadsExactlyAsInTheReadout() {
        // Reused by the 真實位置 confirmation (RealModeCopy), so a jump is spelled the same everywhere.
        assertEquals("850 m", LocationJump.distanceText(850.0))
        assertEquals("3.4 km", LocationJump.distanceText(3_400.0))
        assertEquals("2,230 km", LocationJump.distanceText(2_230_240.0))
    }
}
