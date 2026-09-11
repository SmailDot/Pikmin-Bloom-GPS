package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.Decor
import app.pikminbloom.gps.data.OverpassClient.DecorHit
import app.pikminbloom.gps.data.TravelMode
import java.util.Locale

/**
 * The pure, string-producing half of the decor hunt: filtering the catalogue, formatting distances
 * and purity, and naming the waypoint / route that comes out of a pick. No Android types, so every
 * rule here is unit-tested (DecorHuntFormatTest) without a device.
 *
 * Strings are Traditional Chinese by design, like `TravelMode.label` and the route names in
 * `WaypointStore`; the dialog chrome around them lives in `res/values/strings_decor.xml`.
 */
object DecorHuntFormat {

    /** Waypoint radius limits enforced by `WaypointStore` (and `WaypointDialogs`). */
    const val MIN_WAYPOINT_RADIUS_M = 8.0
    const val MAX_WAYPOINT_RADIUS_M = 40.0

    /** Dwell limit enforced by `WaypointStore`, in minutes. */
    const val MAX_WANDER_MIN = 30

    /** How many competing decor names to spell out before collapsing to "等 N 種". */
    const val MAX_COMPETING_NAMES = 3

    const val PURITY_CHECKING = "檢查中…"
    const val PURITY_PURE = "純點"
    private const val PURITY_MIXED_PREFIX = "混合："

    /** True when [query] (trimmed; blank matches everything) occurs in the decor or place name. */
    fun matchesFilter(decor: Decor, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return decor.decorName.contains(q, ignoreCase = true) || decor.placeName.contains(q, ignoreCase = true)
    }

    fun filterDecor(query: String, entries: List<Decor> = Decor.entries): List<Decor> =
        entries.filter { matchesFilter(it, query) }

    /** "850 公尺" below one kilometre, otherwise "1.2 公里". */
    fun formatDistance(distanceM: Double): String {
        val m = distanceM.coerceAtLeast(0.0)
        return if (m < 1_000.0) String.format(Locale.US, "%.0f 公尺", m)
        else String.format(Locale.US, "%.1f 公里", m / 1_000.0)
    }

    /**
     * Purity marker for a result row: 「檢查中…」 until [competing] is known, 「純點」 when nothing else
     * is inside `Decor.PURITY_RADIUS_M`, else 「混合：貼紙、公園、廚師帽子 等 5 種」.
     */
    fun purityLabel(competing: List<Decor>?): String = when {
        competing == null -> PURITY_CHECKING
        competing.isEmpty() -> PURITY_PURE
        else -> PURITY_MIXED_PREFIX + competingNames(competing)
    }

    fun purityLabel(hit: DecorHit): String = purityLabel(hit.competingDecor)

    /** "貼紙、公園、廚師帽子", or "貼紙、公園、廚師帽子 等 5 種" when more than [max] compete. */
    fun competingNames(competing: List<Decor>, max: Int = MAX_COMPETING_NAMES): String {
        val distinct = competing.distinct()
        val shown = distinct.take(max).joinToString("、") { it.decorName }
        return if (distinct.size > max) "$shown 等 ${distinct.size} 種" else shown
    }

    /** Waypoint name for a picked place: "瓶蓋·7-ELEVEN"; an unnamed place falls back to its category. */
    fun waypointName(decor: Decor, placeName: String): String {
        val place = placeName.trim().ifEmpty { decor.placeName }
        return "${decor.decorName}·$place"
    }

    /** Name of the dedicated route created for one decor: "找瓶蓋". */
    fun routeName(decor: Decor): String = "找${decor.decorName}"

    /** The store clamps radii to 8–40 m; the trip planner would happily use 60, hence the hint. */
    fun clampWaypointRadius(radiusM: Double): Double = radiusM.coerceIn(MIN_WAYPOINT_RADIUS_M, MAX_WAYPOINT_RADIUS_M)

    fun clampWanderMinutes(minutes: Int): Int = minutes.coerceIn(0, MAX_WANDER_MIN)

    /** "步行（4.7 km/h）", "汽機車（45 km/h）" - whole numbers drop the decimal. */
    fun travelModeLabel(mode: TravelMode): String {
        val kmh = mode.speedKmh
        val speed = if (kmh == kmh.toLong().toDouble()) kmh.toLong().toString() else String.format(Locale.US, "%.1f", kmh)
        return "${mode.label}（$speed km/h）"
    }
}
