package app.pikminbloom.gps

import app.pikminbloom.gps.data.OverpassClient
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassClientTest {

    private val center = LatLng(22.7583, 120.3379)

    @Test
    fun queryContainsRadiusAndCenterInUsLocale() {
        val q = OverpassClient.buildQuery(center, 1500)
        assertTrue(q.contains("around:1500,22.758300,120.337900"))
        assertTrue(q.contains("[out:json]"))
        assertTrue(q.contains("out center tags;"))
    }

    @Test
    fun parsesNodesAndWayCentersAndPrefersLocalName() {
        val json = """
        {"elements":[
          {"type":"node","id":1,"lat":22.7590,"lon":120.3380,
           "tags":{"historic":"memorial","name":"忠烈祠","name:en":"Martyrs Shrine"}},
          {"type":"way","id":2,"center":{"lat":22.7600,"lon":120.3390},
           "tags":{"leisure":"playground","name":"社區公園遊戲場"}}
        ]}
        """
        val out = OverpassClient.parse(json, center, limit = 10)
        assertEquals(2, out.size)
        // historic outranks leisure=playground, so the shrine comes first.
        assertEquals("忠烈祠", out[0].name)
        assertEquals(22.7590, out[0].lat, 1e-6)
        assertEquals("社區公園遊戲場", out[1].name)
        assertEquals(22.7600, out[1].lat, 1e-6)
    }

    @Test
    fun dropsPrivateMilitaryAndUntaggedElements() {
        val json = """
        {"elements":[
          {"type":"node","id":1,"lat":22.7590,"lon":120.3380,"tags":{"historic":"memorial","access":"private","name":"私人"}},
          {"type":"node","id":2,"lat":22.7591,"lon":120.3381,"tags":{"leisure":"park","landuse":"military","name":"營區"}},
          {"type":"node","id":3,"lat":22.7592,"lon":120.3382,"tags":{"shop":"convenience","name":"超商"}},
          {"type":"node","id":4,"lat":22.7593,"lon":120.3383},
          {"type":"node","id":5,"lat":22.7594,"lon":120.3384,"tags":{"leisure":"pitch"}}
        ]}
        """
        // 1 private, 2 military, 3 not a Wayspot-ish tag, 4 no tags, 5 unnamed generic pitch.
        assertEquals(0, OverpassClient.parse(json, center, limit = 10).size)
    }

    @Test
    fun mergesDuplicatesWithinTwentyFiveMetres() {
        val a = LatLng(22.7590, 120.3380)
        val b = GeoMath.offsetMeters(a, 10.0, 0.0)     // same place, mapped twice
        val c = GeoMath.offsetMeters(a, 80.0, 0.0)     // genuinely different place
        val json = """
        {"elements":[
          {"type":"node","id":1,"lat":${a.lat},"lon":${a.lon},"tags":{"historic":"monument","name":"A"}},
          {"type":"node","id":2,"lat":${b.lat},"lon":${b.lon},"tags":{"historic":"monument","name":"A 重複"}},
          {"type":"node","id":3,"lat":${c.lat},"lon":${c.lon},"tags":{"historic":"monument","name":"B"}}
        ]}
        """
        val out = OverpassClient.parse(json, center, limit = 10)
        assertEquals(2, out.size)
        assertTrue(out.map { it.name }.containsAll(listOf("A", "B")))
    }

    @Test
    fun respectsLimitAndAppliesWaypointDefaults() {
        val elements = (0 until 10).joinToString(",") { i ->
            """{"type":"node","id":$i,"lat":${22.7590 + i * 0.001},"lon":120.3380,"tags":{"historic":"monument","name":"M$i"}}"""
        }
        val out = OverpassClient.parse("""{"elements":[$elements]}""", center, limit = 3, defaultRadiusM = 25.0, defaultDwellSec = 0)
        assertEquals(3, out.size)
        assertTrue(out.all { it.radiusM == 25.0 && it.dwellSec == 0 })
        assertEquals(out.size, out.map { it.id }.distinct().size)
    }

    @Test
    fun emptyOrMalformedResponsesAreSafe() {
        assertEquals(0, OverpassClient.parse("""{"elements":[]}""", center, 10).size)
        assertEquals(0, OverpassClient.parse("""{"version":0.6}""", center, 10).size)
    }
}
