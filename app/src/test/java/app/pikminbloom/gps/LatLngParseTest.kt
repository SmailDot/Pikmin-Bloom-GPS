package app.pikminbloom.gps

import app.pikminbloom.gps.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the user pastes into a coordinate box: Google Maps output in its various shapes. */
class LatLngParseTest {
    private val tokyo = LatLng(35.6812, 139.7671)

    @Test
    fun commaAndSpaceSeparatedDecimals() {
        assertEquals(tokyo, LatLng.parse("35.6812, 139.7671"))
        assertEquals(tokyo, LatLng.parse("35.6812,139.7671"))
        assertEquals(tokyo, LatLng.parse("35.6812 139.7671"))
        assertEquals(tokyo, LatLng.parse("  35.6812 ,  139.7671  "))
    }

    @Test
    fun googleMapsUrlAndDegreeSignsAreAccepted() {
        assertEquals(tokyo, LatLng.parse("https://www.google.com/maps/@35.6812,139.7671,17z"))
        assertEquals(tokyo, LatLng.parse("35.6812°N 139.7671°E"))
        assertEquals(LatLng(-33.8688, 151.2093), LatLng.parse("33.8688°S 151.2093°E"))
        assertEquals(LatLng(-33.8688, 151.2093), LatLng.parse("-33.8688, 151.2093"))
    }

    @Test
    fun garbageAndOutOfRangeAreNull() {
        assertNull(LatLng.parse(null))
        assertNull(LatLng.parse(""))
        assertNull(LatLng.parse("abc"))
        assertNull(LatLng.parse("35.6812"))
        assertNull(LatLng.parse("95.0, 139.7"))
        assertNull(LatLng.parse("35.6, 181.0"))
    }
}
