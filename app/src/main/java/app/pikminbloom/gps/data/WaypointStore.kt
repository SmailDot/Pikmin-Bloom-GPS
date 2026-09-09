package app.pikminbloom.gps.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.UUID

/** Big Flower list persisted as JSON in the app's private files dir. Process-wide singleton. */
class WaypointStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val _waypoints = MutableStateFlow(readFile())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints

    fun load(): List<Waypoint> = _waypoints.value

    @Synchronized
    fun save(list: List<Waypoint>) {
        _waypoints.value = list
        runCatching { file.writeText(toJson(list)) }
            .onFailure { Log.w(TAG, "save waypoints failed", it) }
    }

    fun add(wp: Waypoint) = save(load() + wp)

    fun update(wp: Waypoint) = save(load().map { if (it.id == wp.id) wp else it })

    fun remove(id: String) = save(load().filterNot { it.id == id })

    fun move(from: Int, to: Int) {
        val list = load().toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        save(list)
    }

    fun clear() = save(emptyList())

    fun exportJson(): String = toJson(load())

    /** Replaces the list with the JSON export format (array of {name,lat,lon,radiusM,dwellSec}). */
    fun importJson(text: String): Int {
        val parsed = fromJson(text)
        save(parsed)
        return parsed.size
    }

    fun exportGpx(): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"PikminBloomGPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (w in load()) {
            sb.append("  <wpt lat=\"${w.lat}\" lon=\"${w.lon}\">\n")
            sb.append("    <name>${escape(w.name)}</name>\n")
            sb.append("    <desc>radius=${w.radiusM};dwell=${w.dwellSec}</desc>\n")
            sb.append("  </wpt>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Imports every `<wpt>` (and, if there are none, `<rtept>`/`<trkpt>`) as waypoints. */
    fun importGpx(text: String, defaultRadiusM: Double, defaultDwellSec: Int): Int {
        val out = ArrayList<Waypoint>()
        val fallback = ArrayList<Waypoint>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(text))
            var lat: Double? = null
            var lon: Double? = null
            var name: String? = null
            var desc: String? = null
            var tag: String? = null
            var inPoint = false
            var pointTag = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val n = parser.name
                        if (n == "wpt" || n == "rtept" || n == "trkpt") {
                            inPoint = true; pointTag = n
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            name = null; desc = null
                        } else if (inPoint) tag = n
                    }
                    XmlPullParser.TEXT -> if (inPoint) {
                        when (tag) {
                            "name" -> name = parser.text.trim()
                            "desc" -> desc = parser.text.trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val n = parser.name
                        if (inPoint && n == pointTag) {
                            val la = lat; val lo = lon
                            if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                                var radius = defaultRadiusM
                                var dwell = defaultDwellSec
                                desc?.split(';')?.forEach { part ->
                                    val kv = part.split('=')
                                    if (kv.size == 2) when (kv[0].trim()) {
                                        "radius" -> kv[1].trim().toDoubleOrNull()?.let { radius = it }
                                        "dwell" -> kv[1].trim().toIntOrNull()?.let { dwell = it }
                                    }
                                }
                                val wp = Waypoint(
                                    id = UUID.randomUUID().toString(),
                                    name = name?.takeIf { it.isNotBlank() } ?: "大花 ${out.size + fallback.size + 1}",
                                    lat = la, lon = lo,
                                    radiusM = radius.coerceIn(8.0, 40.0),
                                    dwellSec = dwell.coerceIn(0, 1800),
                                )
                                if (n == "wpt") out.add(wp) else fallback.add(wp)
                            }
                            inPoint = false
                        }
                        if (inPoint) tag = null
                    }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GPX parse failed", t)
            return 0
        }
        val result = if (out.isNotEmpty()) out else fallback
        if (result.isNotEmpty()) save(result)
        return result.size
    }

    private fun readFile(): List<Waypoint> = try {
        if (file.exists()) fromJson(file.readText()) else emptyList()
    } catch (t: Throwable) {
        Log.w(TAG, "read waypoints failed", t); emptyList()
    }

    private fun toJson(list: List<Waypoint>): String {
        val arr = JSONArray()
        for (w in list) {
            arr.put(JSONObject().apply {
                put("id", w.id); put("name", w.name); put("lat", w.lat); put("lon", w.lon)
                put("radiusM", w.radiusM); put("dwellSec", w.dwellSec)
            })
        }
        return JSONObject().put("version", 1).put("waypoints", arr).toString(2)
    }

    private fun fromJson(text: String): List<Waypoint> {
        val trimmed = text.trim()
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("waypoints") ?: JSONArray()
        val out = ArrayList<Waypoint>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            out.add(
                Waypoint(
                    id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    name = o.optString("name").takeIf { it.isNotBlank() } ?: "大花 ${i + 1}",
                    lat = lat, lon = lon,
                    radiusM = o.optDouble("radiusM", Waypoint.DEFAULT_RADIUS_M).coerceIn(8.0, 40.0),
                    dwellSec = o.optInt("dwellSec", Waypoint.DEFAULT_DWELL_SEC).coerceIn(0, 1800),
                )
            )
        }
        return out
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val TAG = "PikminGPS"
        private const val FILE_NAME = "waypoints.json"

        @Volatile private var instance: WaypointStore? = null

        fun get(context: Context): WaypointStore =
            instance ?: synchronized(this) { instance ?: WaypointStore(context).also { instance = it } }
    }
}
