package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.Decor
import app.pikminbloom.gps.data.OverpassClient.DecorHit
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.i18n.tr
import java.util.Locale

/**
 * The pure, string-producing half of the decor hunt: filtering the catalogue, formatting distances
 * and purity, and naming the waypoint / route that comes out of a pick. No Android types, so every
 * rule here is unit-tested (DecorHuntFormatTest) without a device.
 *
 * Strings follow the app's language ([Lang]), like `TravelMode.label` and `Decor.decorName`; the dialog chrome
 * around them lives in the strings_decor.xml of each language.
 */
object DecorHuntFormat {

    /** Waypoint radius limits WaypointStore clamps to on load (a decor trip's wander radius). */
    const val MIN_WAYPOINT_RADIUS_M = 8.0
    const val MAX_WAYPOINT_RADIUS_M = 40.0

    /** Dwell limit enforced by `WaypointStore`, in minutes. */
    const val MAX_WANDER_MIN = 30

    /** How many competing decor names to spell out before collapsing to "等 N 種". */
    const val MAX_COMPETING_NAMES = 3

    fun purityChecking(lang: Lang = Lang.current): String = tr("檢查中…", "Checking…", "確認中…", lang)

    fun purityPure(lang: Lang = Lang.current): String = tr("純點", "Pure spot", "単一スポット", lang)

    /**
     * True when [query] (trimmed; blank matches everything) occurs in the decor or place name, in any of the
     * three languages: a filter typed in English still finds 「咖啡杯」 on a Chinese phone.
     */
    fun matchesFilter(decor: Decor, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return decor.nameContains(q)
    }

    fun filterDecor(query: String, entries: List<Decor> = Decor.entries): List<Decor> =
        entries.filter { matchesFilter(it, query) }

    /** "850 公尺" below one kilometre, otherwise "1.2 公里"; "850 m" / "1.2 km" in English and Japanese. */
    fun formatDistance(distanceM: Double, lang: Lang = Lang.current): String {
        val m = distanceM.coerceAtLeast(0.0)
        return if (m < 1_000.0) String.format(Locale.US, tr("%.0f 公尺", "%.0f m", "%.0f m", lang), m)
        else String.format(Locale.US, tr("%.1f 公里", "%.1f km", "%.1f km", lang), m / 1_000.0)
    }

    /**
     * Purity marker for a result row: 「檢查中…」 until [competing] is known, 「純點」 when nothing else
     * is inside `Decor.PURITY_RADIUS_M`, else 「混合：貼紙、公園、廚師帽子 等 5 種」.
     */
    fun purityLabel(competing: List<Decor>?, lang: Lang = Lang.current): String = when {
        competing == null -> purityChecking(lang)
        competing.isEmpty() -> purityPure(lang)
        else -> tr("混合：", "Mixed: ", "混在：", lang) + competingNames(competing, lang = lang)
    }

    fun purityLabel(hit: DecorHit): String = purityLabel(hit.competingDecor)

    /** "貼紙、公園、廚師帽子", or "貼紙、公園、廚師帽子 等 5 種" when more than [max] compete. */
    fun competingNames(competing: List<Decor>, max: Int = MAX_COMPETING_NAMES, lang: Lang = Lang.current): String {
        val distinct = competing.distinct()
        val shown = distinct.take(max).joinToString(tr("、", ", ", "、", lang)) { it.decorName }
        if (distinct.size <= max) return shown
        val n = distinct.size
        return tr("$shown 等 $n 種", "$shown and more ($n kinds)", "$shown など $n 種類", lang)
    }

    /** Waypoint name for a picked place: "瓶蓋·7-ELEVEN"; an unnamed place falls back to its category. */
    fun waypointName(decor: Decor, placeName: String): String {
        val place = placeName.trim().ifEmpty { decor.placeName }
        return "${decor.decorName}·$place"
    }

    /** Name of the dedicated route created for one decor: "找瓶蓋". */
    fun routeName(decor: Decor, lang: Lang = Lang.current): String =
        tr("找${decor.decorName}", "Find: ${decor.decorName}", "${decor.decorName}探し", lang)

    /** The store clamps radii to 8–40 m; the trip planner would happily use 60, hence the hint. */
    fun clampWaypointRadius(radiusM: Double): Double = radiusM.coerceIn(MIN_WAYPOINT_RADIUS_M, MAX_WAYPOINT_RADIUS_M)

    fun clampWanderMinutes(minutes: Int): Int = minutes.coerceIn(0, MAX_WANDER_MIN)

    /** "步行（4.7 km/h）", "其他（45 km/h）" - whole numbers drop the decimal. */
    fun travelModeLabel(mode: TravelMode, config: PatrolConfig, lang: Lang = Lang.current): String {
        val kmh = config.speedTextOf(mode)
        return if (lang == Lang.EN) "${mode.labelIn(lang)} ($kmh km/h)" else "${mode.labelIn(lang)}（$kmh km/h）"
    }
}
