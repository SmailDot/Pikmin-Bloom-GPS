package app.pikminbloom.gps

import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.ui.CenterPick
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The crosshair pick saves exactly what it shows, and never builds an invalid LatLng. */
class CenterPickTest {
    @Test
    fun anOrdinaryCentreIsKeptExactly() {
        val p = CenterPick.toLatLng(35.681236, 139.767125)
        assertEquals(35.681236, p.lat, 0.0)
        assertEquals(139.767125, p.lon, 0.0)
    }

    @Test
    fun aCentreOnARepeatedWorldToTheEastFoldsBack() {
        assertEquals(-170.0, CenterPick.toLatLng(10.0, 190.0).lon, 1e-9)
    }

    @Test
    fun aCentreOnARepeatedWorldToTheWestFoldsBack() {
        assertEquals(179.0, CenterPick.toLatLng(10.0, -181.0).lon, 1e-9)
    }

    @Test
    fun latitudeIsClampedToTheMapsOwnLimit() {
        assertEquals("the Web-Mercator limit the map itself stops at", 85.0511, CenterPick.MAX_LAT, 1e-4)
        assertEquals(CenterPick.MAX_LAT, CenterPick.toLatLng(89.9, 0.0).lat, 0.0)
        assertEquals(-CenterPick.MAX_LAT, CenterPick.toLatLng(-89.9, 0.0).lat, 0.0)
    }

    @Test
    fun coordTextHasSixDecimalsAndADotInAnyLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("35.681236, 139.767125", CenterPick.coordText(LatLng(35.6812361, 139.7671249)))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
