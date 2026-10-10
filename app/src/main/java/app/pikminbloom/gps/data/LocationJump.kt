package app.pikminbloom.gps.data

import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.i18n.tr
import org.json.JSONObject
import java.util.Locale

/**
 * The last time the game saw the avatar's position jump: a teleport, a switch to the real GPS
 * (真實位置, 停止 away from it, 放棄 a checkpoint) or back (回到虛擬位置). Informational only: no rule
 * is known for how long after a jump the game stays calm (PLAN L1), so nothing is ever blocked on it.
 */
data class LocationJump(val atMs: Long, val distanceM: Double) {

    fun toJson(): String = JSONObject().put("atMs", atMs).put("distanceM", distanceM).toString()

    companion object {
        /** Shorter than this is not a jump worth reporting. */
        const val MIN_REPORTED_M = 100.0
        /** After a day the readout is noise and disappears. */
        const val SHOW_FOR_MS = 24 * 60 * 60 * 1000L

        fun of(atMs: Long, from: LatLng, to: LatLng): LocationJump? =
            GeoMath.distanceM(from, to).takeIf { it >= MIN_REPORTED_M }?.let { LocationJump(atMs, it) }

        fun fromJson(text: String?): LocationJump? = runCatching {
            val o = JSONObject(text!!)
            LocationJump(o.getLong("atMs"), o.getDouble("distanceM"))
        }.getOrNull()

        /** "850 m", "3.4 km", "1,850 km" in any locale: how a jump's distance is spelled everywhere. */
        fun distanceText(distanceM: Double): String = when {
            distanceM < 1_000.0 -> String.format(Locale.US, "%.0f m", distanceM)
            distanceM < 10_000.0 -> String.format(Locale.US, "%.1f km", distanceM / 1_000.0)
            else -> String.format(Locale.US, "%,.0f km", distanceM / 1_000.0)
        }

        /** "上次跳躍 1,850 km · 2 小時 5 分鐘前", or null when there is none in the last day. */
        fun readout(jump: LocationJump?, nowMs: Long, lang: Lang = Lang.current): String? {
            if (jump == null) return null
            val ageMs = (nowMs - jump.atMs).coerceAtLeast(0L)
            if (ageMs > SHOW_FOR_MS) return null
            val distance = distanceText(jump.distanceM)
            val minutes = ageMs / 60_000L
            val h = minutes / 60
            val m = minutes % 60
            val age = if (minutes < 60) tr("$minutes 分鐘前", "$minutes min ago", "$minutes 分前", lang)
            else tr("$h 小時 $m 分鐘前", "$h h $m min ago", "$h 時間 $m 分前", lang)
            return tr("上次瞬移 $distance · $age", "Last jump $distance · $age", "前回のジャンプ $distance · $age", lang)
        }
    }
}
