package app.pikminbloom.gps.data

import android.util.Log
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Finds candidate Big Flower locations from OpenStreetMap via the public Overpass API.
 *
 * Pikmin Bloom's Big Flowers sit on Niantic Wayspots, and Wayspots are curated from the same kinds
 * of real-world points of interest that OpenStreetMap tags (memorials, artwork, playgrounds, places
 * of worship, park features...). Niantic publishes no Big Flower list, so this returns *candidates*:
 * expect roughly a third to two thirds of them to actually carry a Big Flower. The user keeps the
 * ones that do.
 *
 * OpenStreetMap data is ODbL; attribution is only required when redistributing, and a private
 * waypoint list on the user's phone is not redistribution.
 */
object OverpassClient {

    private const val TAG = "PikminGPS"
    private const val USER_AGENT = "PikminBloomGPS/1.0 (personal waypoint import)"

    /** Mirrors are tried in order; the public instance is rate limited and sometimes busy. */
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /** Tag groups ranked by how likely Niantic is to have accepted them as a Wayspot. */
    private val RANKS: List<Pair<String, Int>> = listOf(
        "historic" to 0,
        "memorial" to 0,
        "tourism:artwork" to 0,
        "amenity:place_of_worship" to 1,
        "tourism:museum" to 1,
        "tourism:gallery" to 1,
        "tourism:attraction" to 1,
        "man_made" to 1,
        "leisure:playground" to 2,
        "amenity:library" to 2,
        "amenity:community_centre" to 2,
        "amenity:theatre" to 2,
        "amenity:arts_centre" to 2,
        "amenity:fountain" to 2,
        "tourism:viewpoint" to 3,
        "tourism:picnic_site" to 3,
        "leisure:garden" to 3,
        "leisure:bandstand" to 3,
        "leisure:fitness_station" to 3,
        "amenity:post_office" to 4,
        "amenity:townhall" to 4,
        "leisure:park" to 4,
        "leisure:pitch" to 5,
    )

    /** Two candidates closer than this are the same real-world place. */
    private const val DEDUPE_M = 25.0

    class OverpassException(message: String) : Exception(message)

    /**
     * Queries every mirror in turn until one answers.
     *
     * @param radiusM search radius around [center], metres (the API is billed by area, keep it sane)
     * @param limit   maximum waypoints to return, best-ranked first
     */
    suspend fun findCandidates(
        center: LatLng,
        radiusM: Int = 1500,
        limit: Int = 40,
        defaultRadiusM: Double = Waypoint.DEFAULT_RADIUS_M,
        defaultDwellSec: Int = Waypoint.DEFAULT_DWELL_SEC,
    ): List<Waypoint> = withContext(Dispatchers.IO) {
        val query = buildQuery(center, radiusM.coerceIn(100, 5000))
        var lastError: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                val body = post(endpoint, query)
                return@withContext parse(body, center, limit, defaultRadiusM, defaultDwellSec)
            } catch (t: Exception) {
                Log.w(TAG, "Overpass $endpoint failed: ${t.message}")
                lastError = t
            }
        }
        throw OverpassException(lastError?.message ?: "no Overpass mirror answered")
    }

    fun buildQuery(center: LatLng, radiusM: Int): String {
        val a = "around:$radiusM,${"%.6f".format(java.util.Locale.US, center.lat)},${"%.6f".format(java.util.Locale.US, center.lon)}"
        return """
            [out:json][timeout:90];
            (
              nwr($a)["historic"]["historic"!="boundary_stone"];
              nwr($a)["memorial"];
              nwr($a)["tourism"~"^(artwork|museum|gallery|attraction|viewpoint|picnic_site)${'$'}"];
              nwr($a)["amenity"~"^(place_of_worship|library|post_office|fountain|townhall|theatre|arts_centre|community_centre)${'$'}"];
              nwr($a)["leisure"~"^(playground|park|pitch|fitness_station|garden|bandstand)${'$'}"];
              nwr($a)["man_made"~"^(water_well|windmill|obelisk|lighthouse)${'$'}"]["name"];
            );
            out center tags;
        """.trimIndent()
    }

    private fun post(endpoint: String, query: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() }.orEmpty().take(200)
                throw OverpassException("HTTP $code $err")
            }
            return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
        } finally {
            conn.disconnect()
        }
    }

    /** Pure parsing/ranking/dedupe step, split out so it can be unit tested without a network. */
    fun parse(
        json: String,
        center: LatLng,
        limit: Int,
        defaultRadiusM: Double = Waypoint.DEFAULT_RADIUS_M,
        defaultDwellSec: Int = Waypoint.DEFAULT_DWELL_SEC,
    ): List<Waypoint> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        data class Candidate(val pos: LatLng, val name: String, val rank: Int, val distance: Double)

        val found = ArrayList<Candidate>(elements.length())
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: continue

            // Places you cannot plant in are worse than useless as patrol targets.
            if (tags.optString("access") == "private") continue
            if (tags.optString("landuse") == "military") continue
            if (tags.optString("military").isNotEmpty()) continue

            val lat: Double
            val lon: Double
            if (el.has("lat") && el.has("lon")) {
                lat = el.optDouble("lat", Double.NaN); lon = el.optDouble("lon", Double.NaN)
            } else {
                val c = el.optJSONObject("center") ?: continue
                lat = c.optDouble("lat", Double.NaN); lon = c.optDouble("lon", Double.NaN)
            }
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            val rank = rankOf(tags) ?: continue
            val name = pickName(tags)
            // Unnamed generic sports pitches are almost never Wayspots.
            if (name == null && rank >= 5) continue

            val pos = LatLng(lat, lon)
            found.add(Candidate(pos, name ?: fallbackName(tags), rank, GeoMath.distanceM(center, pos)))
        }

        // Best rank first, then nearest; drop anything within DEDUPE_M of a better candidate.
        val sorted = found.sortedWith(compareBy({ it.rank }, { it.distance }))
        val kept = ArrayList<Candidate>(minOf(limit, sorted.size))
        for (c in sorted) {
            if (kept.size >= limit) break
            if (kept.any { GeoMath.distanceM(it.pos, c.pos) < DEDUPE_M }) continue
            kept.add(c)
        }

        return kept.map {
            Waypoint(
                id = UUID.randomUUID().toString(),
                name = it.name,
                lat = it.pos.lat,
                lon = it.pos.lon,
                radiusM = defaultRadiusM,
                dwellSec = defaultDwellSec,
            )
        }
    }

    private fun rankOf(tags: JSONObject): Int? {
        var best: Int? = null
        for ((key, rank) in RANKS) {
            val matches = if (key.contains(':')) {
                val (k, v) = key.split(':', limit = 2)
                tags.optString(k) == v
            } else {
                tags.optString(key).isNotEmpty()
            }
            if (matches && (best == null || rank < best!!)) best = rank
        }
        return best
    }

    private fun pickName(tags: JSONObject): String? =
        listOf("name:zh-Hant", "name:zh", "name", "name:en", "official_name")
            .firstNotNullOfOrNull { tags.optString(it).takeIf { s -> s.isNotBlank() } }

    private fun fallbackName(tags: JSONObject): String {
        val kind = listOf("historic", "memorial", "tourism", "amenity", "leisure", "man_made")
            .firstNotNullOfOrNull { tags.optString(it).takeIf { s -> s.isNotBlank() } }
        return kind ?: "候選地點"
    }
}
