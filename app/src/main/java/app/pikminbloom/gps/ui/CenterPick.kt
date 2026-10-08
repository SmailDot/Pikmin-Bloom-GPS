package app.pikminbloom.gps.ui

import app.pikminbloom.gps.geo.LatLng
import java.util.Locale

/** The map-centre pick (準心) for a new home: what the crosshair points at, exactly as it will be saved. */
object CenterPick {
    /** Web-Mercator limit; the map cannot centre beyond it. */
    const val MAX_LAT = 85.05112878

    /** A LatLng that cannot throw: a centre on a repeated copy of the world folds back into [-180, 180). */
    fun toLatLng(lat: Double, lon: Double): LatLng {
        val folded = if (lon >= -180.0 && lon < 180.0) lon else {
            val r = (lon + 180.0) % 360.0
            (if (r < 0.0) r + 360.0 else r) - 180.0
        }
        return LatLng(lat.coerceIn(-MAX_LAT, MAX_LAT), folded)
    }

    /** "35.681236, 139.767125": six decimals (about 0.1 m), dot separator whatever the locale. */
    fun coordText(p: LatLng): String = String.format(Locale.US, "%.6f, %.6f", p.lat, p.lon)
}
