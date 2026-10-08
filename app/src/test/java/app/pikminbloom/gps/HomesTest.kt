package app.pikminbloom.gps

import app.pikminbloom.gps.data.Home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved-homes list (家1／家2／…) survives a JSON round trip through SharedPreferences. */
class HomesTest {

    @Test
    fun roundTripKeepsOrderNamesAndCoordinates() {
        val homes = listOf(
            Home("家 1", 22.741500, 121.132356),
            Home("東京の家 🏠", 35.681236, 139.767125),
        )
        assertEquals(homes, Home.fromJson(Home.toJson(homes)))
    }

    @Test
    fun emptyListRoundTripsToEmpty() {
        assertEquals(emptyList<Home>(), Home.fromJson(Home.toJson(emptyList())))
    }

    @Test
    fun nullBlankAndGarbageDecodeToEmpty() {
        assertEquals(emptyList<Home>(), Home.fromJson(null))
        assertEquals(emptyList<Home>(), Home.fromJson("   "))
        assertEquals(emptyList<Home>(), Home.fromJson("not json"))
        assertEquals(emptyList<Home>(), Home.fromJson("{\"a\":1}"))
    }

    @Test
    fun entriesWithInvalidCoordinatesAreDropped() {
        val text = """[{"name":"ok","lat":1.0,"lon":2.0},{"name":"bad","lat":95.0,"lon":2.0},{"name":"missing"}]"""
        val homes = Home.fromJson(text)
        assertEquals(1, homes.size)
        assertEquals("ok", homes[0].name)
    }

    @Test
    fun latLngMatchesFields() {
        val h = Home("x", 22.5, 120.25)
        assertEquals(22.5, h.latLng.lat, 0.0)
        assertEquals(120.25, h.latLng.lon, 0.0)
        assertTrue(Home.toJson(listOf(h)).contains("\"lon\":120.25"))
    }
}
