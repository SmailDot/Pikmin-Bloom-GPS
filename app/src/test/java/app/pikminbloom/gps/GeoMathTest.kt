package app.pikminbloom.gps

import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    private val taipei101 = LatLng(25.033964, 121.564468)
    private val taipeiStation = LatLng(25.047924, 121.517081)

    @Test
    fun distanceTaipei101ToMainStationIsAboutFiveKm() {
        val d = GeoMath.distanceM(taipei101, taipeiStation)
        assertTrue("distance was $d", d in 4900.0..5150.0)
        assertEquals(d, GeoMath.distanceM(taipeiStation, taipei101), 1e-6)
    }

    @Test
    fun bearingCardinalDirections() {
        val north = GeoMath.offsetMeters(taipei101, 100.0, 0.0)
        val east = GeoMath.offsetMeters(taipei101, 0.0, 100.0)
        assertEquals(0.0, GeoMath.bearingDeg(taipei101, north), 0.5)
        assertEquals(90.0, GeoMath.bearingDeg(taipei101, east), 0.5)
        assertEquals(180.0, GeoMath.bearingDeg(north, taipei101), 0.5)
        assertEquals(270.0, GeoMath.bearingDeg(east, taipei101), 0.5)
    }

    @Test
    fun destinationRoundTrip() {
        val b = GeoMath.destination(taipei101, 45.0, 1000.0)
        assertEquals(1000.0, GeoMath.distanceM(taipei101, b), 0.02)
        assertEquals(45.0, GeoMath.bearingDeg(taipei101, b), 0.01)
        // The reverse bearing of a great circle is not exactly 225° (meridian convergence), so allow a few cm.
        val back = GeoMath.destination(b, 225.0, 1000.0)
        assertEquals(0.0, GeoMath.distanceM(taipei101, back), 0.25)
        val exactBack = GeoMath.destination(b, GeoMath.bearingDeg(b, taipei101), 1000.0)
        assertEquals(0.0, GeoMath.distanceM(taipei101, exactBack), 0.01)
    }

    @Test
    fun interpolateMidpoint() {
        val mid = GeoMath.interpolate(taipei101, taipeiStation, 0.5)
        val total = GeoMath.distanceM(taipei101, taipeiStation)
        assertEquals(total / 2, GeoMath.distanceM(taipei101, mid), 0.05)
        assertEquals(taipei101, GeoMath.interpolate(taipei101, taipeiStation, 0.0))
        assertEquals(taipeiStation, GeoMath.interpolate(taipei101, taipeiStation, 1.0))
    }

    @Test
    fun offsetMetersMatchesHaversine() {
        val p = GeoMath.offsetMeters(taipei101, 30.0, 40.0)
        assertEquals(50.0, GeoMath.distanceM(taipei101, p), 0.05)
    }

    @Test
    fun normalizeBearing() {
        assertEquals(270.0, GeoMath.normalizeBearing(-90.0), 1e-9)
        assertEquals(90.0, GeoMath.normalizeBearing(450.0), 1e-9)
        assertEquals(0.0, GeoMath.normalizeBearing(360.0), 1e-9)
    }
}
