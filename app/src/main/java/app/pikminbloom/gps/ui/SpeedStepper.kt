package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.TravelMode
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The floating bar's 速度 －／＋ ("每次都得跳去巡邏助手 app 才能改太麻煩", 2026-10-03): steps the speed in
 * force right now - walking, or the vehicle override - to the next round value. Walking never passes
 * 20 km/h (Prefs.MAX_SPEED_MPS: the game stops planting above roughly that); vehicles stay inside the
 * range Settings accepts.
 */
object SpeedStepper {
    const val WALK_MIN_KMH = 2.0
    const val WALK_MAX_KMH = 20.0

    fun stepKmh(mode: TravelMode?): Double = when (mode) {
        TravelMode.CAR -> 5.0
        TravelMode.HIGHWAY -> 10.0
        TravelMode.PLANE -> 50.0
        else -> 1.0                        // walking paces (and the retired bike)
    }

    fun next(mode: TravelMode?, currentKmh: Double, up: Boolean): Double {
        val step = stepKmh(mode)
        val raw = if (up) (floor(currentKmh / step + 1e-9) + 1) * step else (ceil(currentKmh / step - 1e-9) - 1) * step
        val onFoot = mode == null || mode.countsSteps
        // The walking floor must not lift a walk that is already under it (Settings can store ~1.08 km/h):
        // "-" leaves such a speed alone, "+" still climbs to the next whole number.
        return if (onFoot) raw.coerceIn(minOf(WALK_MIN_KMH, currentKmh), WALK_MAX_KMH) else raw.coerceIn(Prefs.MIN_VEHICLE_KMH, Prefs.MAX_VEHICLE_KMH)
    }

    /** As stored in SharedPreferences (EditTextPreference strings): "19", "17.5". */
    fun text(kmh: Double): String =
        if (kmh == kmh.toLong().toDouble()) kmh.toLong().toString() else String.format(Locale.US, "%.1f", kmh)
}
