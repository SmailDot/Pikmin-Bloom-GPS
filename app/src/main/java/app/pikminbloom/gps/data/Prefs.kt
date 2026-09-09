package app.pikminbloom.gps.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import app.pikminbloom.gps.geo.LatLng

/**
 * Settings + small persisted state. Keys match `res/xml/preferences.xml`; numeric preferences are
 * stored as Strings (EditTextPreference) so the settings screen can edit them directly.
 */
class Prefs(context: Context) {

    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun config(): PatrolConfig {
        val d = PatrolConfig()
        return PatrolConfig(
            // km/h in the UI, m/s internally; 2.5 m/s (9 km/h) stays under Pikmin's planting cutoff.
            speedMps = (str(KEY_SPEED_KMH, d.speedMps * 3.6) / 3.6).coerceIn(0.3, 2.5),
            speedJitterPct = str(KEY_SPEED_JITTER_PCT, d.speedJitterPct).coerceIn(0.0, 30.0),
            strideM = (str(KEY_STRIDE_CM, d.strideM * 100) / 100.0).coerceIn(0.4, 1.2),
            loopMode = enum(KEY_LOOP_MODE, d.loopMode),
            injectSteps = sp.getBoolean(KEY_INJECT_STEPS, d.injectSteps),
            stepFlushIntervalSec = str(KEY_STEP_FLUSH_SEC, d.stepFlushIntervalSec.toDouble()).toInt().coerceIn(20, 600),
            dailyStepCap = str(KEY_DAILY_STEP_CAP, d.dailyStepCap.toDouble()).toLong().coerceIn(0, 200_000),
            accuracyMinM = str(KEY_ACC_MIN, d.accuracyMinM.toDouble()).toFloat().coerceIn(1f, 30f),
            accuracyMaxM = str(KEY_ACC_MAX, d.accuracyMaxM.toDouble()).toFloat().coerceIn(1f, 50f),
            altitudeM = str(KEY_ALTITUDE, d.altitudeM).coerceIn(-100.0, 4000.0),
            notifyOnArrival = sp.getBoolean(KEY_NOTIFY_ARRIVAL, d.notifyOnArrival),
            returnMode = enum(KEY_RETURN_MODE, d.returnMode),
            mockNetworkProvider = sp.getBoolean(KEY_MOCK_NETWORK, d.mockNetworkProvider),
            mockFusedProvider = sp.getBoolean(KEY_MOCK_FUSED, d.mockFusedProvider),
            useFlpMockMode = sp.getBoolean(KEY_FLP_MOCK, d.useFlpMockMode),
            tickMs = d.tickMs,
        ).let { c -> if (c.accuracyMaxM < c.accuracyMinM) c.copy(accuracyMaxM = c.accuracyMinM) else c }
    }

    var defaultRadiusM: Double
        get() = str(KEY_DEFAULT_RADIUS, Waypoint.DEFAULT_RADIUS_M).coerceIn(8.0, 40.0)
        set(v) = sp.edit { putString(KEY_DEFAULT_RADIUS, v.toString()) }

    var defaultDwellSec: Int
        get() = str(KEY_DEFAULT_DWELL, Waypoint.DEFAULT_DWELL_SEC.toDouble()).toInt().coerceIn(0, 1800)
        set(v) = sp.edit { putString(KEY_DEFAULT_DWELL, v.toString()) }

    var home: LatLng?
        get() {
            if (!sp.contains(KEY_HOME_LAT)) return null
            val lat = java.lang.Double.longBitsToDouble(sp.getLong(KEY_HOME_LAT, 0))
            val lon = java.lang.Double.longBitsToDouble(sp.getLong(KEY_HOME_LON, 0))
            return runCatching { LatLng(lat, lon) }.getOrNull()
        }
        set(v) = sp.edit {
            if (v == null) {
                remove(KEY_HOME_LAT); remove(KEY_HOME_LON); remove(KEY_HOME_SAVED_AT)
            } else {
                putLong(KEY_HOME_LAT, java.lang.Double.doubleToRawLongBits(v.lat))
                putLong(KEY_HOME_LON, java.lang.Double.doubleToRawLongBits(v.lon))
                putLong(KEY_HOME_SAVED_AT, System.currentTimeMillis())
            }
        }

    val homeSavedAtMs: Long get() = sp.getLong(KEY_HOME_SAVED_AT, 0L)
    val savedHomeAgeMs: Long get() = if (homeSavedAtMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - homeSavedAtMs

    var lastPosition: LatLng?
        get() {
            if (!sp.contains(KEY_LAST_LAT)) return null
            val lat = java.lang.Double.longBitsToDouble(sp.getLong(KEY_LAST_LAT, 0))
            val lon = java.lang.Double.longBitsToDouble(sp.getLong(KEY_LAST_LON, 0))
            return runCatching { LatLng(lat, lon) }.getOrNull()
        }
        set(v) = sp.edit {
            if (v == null) { remove(KEY_LAST_LAT); remove(KEY_LAST_LON) } else {
                putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(v.lat))
                putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(v.lon))
            }
        }

    var disclaimerAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLAIMER, false)
        set(v) = sp.edit { putBoolean(KEY_DISCLAIMER, v) }

    private fun str(key: String, default: Double): Double =
        sp.getString(key, null)?.trim()?.toDoubleOrNull() ?: default

    private inline fun <reified E : Enum<E>> enum(key: String, default: E): E {
        val raw = sp.getString(key, null) ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
    }

    companion object {
        const val KEY_SPEED_KMH = "speed_kmh"
        const val KEY_SPEED_JITTER_PCT = "speed_jitter_pct"
        const val KEY_STRIDE_CM = "stride_cm"
        const val KEY_LOOP_MODE = "loop_mode"
        const val KEY_INJECT_STEPS = "inject_steps"
        const val KEY_STEP_FLUSH_SEC = "step_flush_sec"
        const val KEY_DAILY_STEP_CAP = "daily_step_cap"
        const val KEY_ACC_MIN = "accuracy_min_m"
        const val KEY_ACC_MAX = "accuracy_max_m"
        const val KEY_ALTITUDE = "altitude_m"
        const val KEY_NOTIFY_ARRIVAL = "notify_on_arrival"
        const val KEY_RETURN_MODE = "return_mode"
        const val KEY_MOCK_NETWORK = "mock_network"
        const val KEY_MOCK_FUSED = "mock_fused"
        const val KEY_FLP_MOCK = "flp_mock_mode"
        const val KEY_DEFAULT_RADIUS = "default_radius_m"
        const val KEY_DEFAULT_DWELL = "default_dwell_sec"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LON = "home_lon"
        const val KEY_HOME_SAVED_AT = "home_saved_at"
        const val KEY_LAST_LAT = "last_lat"
        const val KEY_LAST_LON = "last_lon"
        const val KEY_DISCLAIMER = "disclaimer_accepted"
    }
}
