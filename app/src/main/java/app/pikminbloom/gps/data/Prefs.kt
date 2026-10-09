package app.pikminbloom.gps.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.vision.Calibration

/**
 * Settings + small persisted state. Keys match `res/xml/preferences.xml`; numeric preferences are
 * stored as Strings (EditTextPreference) so the settings screen can edit them directly.
 */
class Prefs(context: Context) {

    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun config(): PatrolConfig {
        val d = PatrolConfig()
        return PatrolConfig(
            // km/h in the UI, m/s internally. Pikmin Bloom stops planting somewhere around
            // 15-20 km/h, so 20 km/h (5.56 m/s) is the highest value we allow.
            speedMps = (str(KEY_SPEED_KMH, DEFAULT_WALK_KMH) / 3.6).coerceIn(0.3, MAX_SPEED_MPS),
            vehicleSpeedsKmh = VEHICLE_SPEED_KEYS.mapValues { (mode, key) ->
                str(key, mode.speedKmh).coerceIn(MIN_VEHICLE_KMH, MAX_VEHICLE_KMH)
            },
            strideM = (str(KEY_STRIDE_CM, d.strideM * 100) / 100.0).coerceIn(0.4, 1.2),
            loopMode = enum(KEY_LOOP_MODE, d.loopMode),
            orbitAtWaypoints = sp.getBoolean(KEY_ORBIT, d.orbitAtWaypoints),
            injectSteps = sp.getBoolean(KEY_INJECT_STEPS, d.injectSteps),
            stepFlushIntervalSec = str(KEY_STEP_FLUSH_SEC, d.stepFlushIntervalSec.toDouble()).toInt().coerceIn(20, 600),
            dailyStepCap = app.pikminbloom.gps.steps.DailyLedger.parseCap(sp.getString(KEY_DAILY_STEP_CAP, null)),
            notifyOnArrival = sp.getBoolean(KEY_NOTIFY_ARRIVAL, d.notifyOnArrival),
            vibrateOnArrival = sp.getBoolean(KEY_VIBRATE_ARRIVAL, d.vibrateOnArrival),
            arrivalAlertMinGapSec = str(KEY_ALERT_GAP_SEC, d.arrivalAlertMinGapSec.toDouble()).toInt().coerceIn(0, 3600),
            autoReturnAfterLaps = str(KEY_AUTO_RETURN_LAPS, d.autoReturnAfterLaps.toDouble()).toInt().coerceIn(0, 999),
            mockNetworkProvider = sp.getBoolean(KEY_MOCK_NETWORK, d.mockNetworkProvider),
            mockFusedProvider = sp.getBoolean(KEY_MOCK_FUSED, d.mockFusedProvider),
            useFlpMockMode = sp.getBoolean(KEY_FLP_MOCK, d.useFlpMockMode),
            tickMs = d.tickMs,
        )
    }

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

    /**
     * Places 回家 can send the avatar to besides the real position (家 1／家 2／…). A patrol never
     * starts from one of these - that would be a teleport - it only ends at one, parked with the
     * mock still on. Empty = 回家 always means the real position.
     */
    var homes: List<Home>
        get() {
            // One-time migration from the single custom home of 1.2.0.
            if (sp.contains(KEY_CUSTOM_HOME_LAT)) {
                val lat = java.lang.Double.longBitsToDouble(sp.getLong(KEY_CUSTOM_HOME_LAT, 0))
                val lon = java.lang.Double.longBitsToDouble(sp.getLong(KEY_CUSTOM_HOME_LON, 0))
                val migrated = runCatching { LatLng(lat, lon) }.map { listOf(Home(Home.defaultName(1), it.lat, it.lon)) }.getOrDefault(emptyList())
                sp.edit { remove(KEY_CUSTOM_HOME_LAT); remove(KEY_CUSTOM_HOME_LON); putString(KEY_HOMES, Home.toJson(migrated)) }
            }
            return Home.fromJson(sp.getString(KEY_HOMES, null))
        }
        set(v) = sp.edit { putString(KEY_HOMES, Home.toJson(v)) }

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

    /**
     * 自動拉花: tap the Big Flower / its label / swipe on arrival. Off by default; needs the accessibility service.
     * Always off in release builds ([AUTO_NECTAR_AVAILABLE]), whatever an earlier debug install stored.
     */
    var autoNectar: Boolean
        get() = AUTO_NECTAR_AVAILABLE && sp.getBoolean(KEY_AUTO_NECTAR, false)
        set(v) = sp.edit { putBoolean(KEY_AUTO_NECTAR, v) }

    /** 自動探險 (experimental): the floating bar's expedition button shows and works. Off by default. */
    var autoExpedition: Boolean
        get() = sp.getBoolean(KEY_AUTO_EXPEDITION, false)
        set(v) = sp.edit { putBoolean(KEY_AUTO_EXPEDITION, v) }

    /** 開 Google 地圖時自動切到真實位置 (9c). Off by default; needs usage access. */
    var autoRealForMaps: Boolean
        get() = sp.getBoolean(KEY_AUTO_REAL_FOR_MAPS, false)
        set(v) = sp.edit { putBoolean(KEY_AUTO_REAL_FOR_MAPS, v) }

    /** 前往後停在這裡 asks how to get there beyond this many km on foot (ui/TripChoices); 0 = never asks. */
    val stayAskKm: Double
        get() = str(KEY_STAY_ASK_KM, app.pikminbloom.gps.ui.TripChoices.DEFAULT_ASK_KM).coerceAtLeast(0.0)

    /** 「不再提醒」 on the Google 定位準確度 advice before a start (ui/LocationAccuracyAdvice). */
    var accuracyAdviceMuted: Boolean
        get() = sp.getBoolean(KEY_ACCURACY_ADVICE_MUTED, false)
        set(v) = sp.edit { putBoolean(KEY_ACCURACY_ADVICE_MUTED, v) }

    var disclaimerAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLAIMER, false)
        set(v) = sp.edit { putBoolean(KEY_DISCLAIMER, v) }

    /** Show the floating control bar (ui/OverlayService) automatically while a patrol runs. */
    var overlayEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_OVERLAY_ENABLED, v) }

    /** Pinned overlays stay on screen when the patrol goes back to IDLE (long-press the handle). */
    var overlayPinned: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_PINNED, false)
        set(v) = sp.edit { putBoolean(KEY_OVERLAY_PINNED, v) }

    /** The floating joystick's size (ui/JoystickLayout.SIZES_DP) and position in pixels ([OVERLAY_UNSET] = default corner). */
    var joystickSizeDp: Int
        get() = app.pikminbloom.gps.ui.JoystickLayout.validSize(sp.getInt(KEY_JOYSTICK_SIZE, app.pikminbloom.gps.ui.JoystickLayout.DEFAULT_SIZE_DP))
        set(v) = sp.edit { putInt(KEY_JOYSTICK_SIZE, v) }

    var joystickX: Int
        get() = sp.getInt(KEY_JOYSTICK_X, OVERLAY_UNSET)
        set(v) = sp.edit { putInt(KEY_JOYSTICK_X, v) }

    var joystickY: Int
        get() = sp.getInt(KEY_JOYSTICK_Y, OVERLAY_UNSET)
        set(v) = sp.edit { putInt(KEY_JOYSTICK_Y, v) }

    /** Last position of the floating window in pixels; [OVERLAY_UNSET] means "never moved". */
    var overlayX: Int
        get() = sp.getInt(KEY_OVERLAY_X, OVERLAY_UNSET)
        set(v) = sp.edit { putInt(KEY_OVERLAY_X, v) }

    var overlayY: Int
        get() = sp.getInt(KEY_OVERLAY_Y, OVERLAY_UNSET)
        set(v) = sp.edit { putInt(KEY_OVERLAY_Y, v) }

    /**
     * The last successful bird's-eye scan calibration (vision/FlowerScanner). A second scan at the
     * same map zoom can adopt it after a short check instead of walking the full 40 m baseline.
     */
    var scanCalibration: Calibration?
        get() {
            if (!sp.contains(KEY_SCAN_CAL_MPP)) return null
            val mpp = java.lang.Double.longBitsToDouble(sp.getLong(KEY_SCAN_CAL_MPP, 0))
            val north = java.lang.Double.longBitsToDouble(sp.getLong(KEY_SCAN_CAL_NORTH, 0))
            return Calibration(mpp, north).takeIf { it.isPlausible() }
        }
        set(v) = sp.edit {
            if (v == null) {
                remove(KEY_SCAN_CAL_MPP); remove(KEY_SCAN_CAL_NORTH); remove(KEY_SCAN_CAL_SAVED_AT)
            } else {
                putLong(KEY_SCAN_CAL_MPP, java.lang.Double.doubleToRawLongBits(v.metresPerPixel))
                putLong(KEY_SCAN_CAL_NORTH, java.lang.Double.doubleToRawLongBits(v.screenNorthDeg))
                putLong(KEY_SCAN_CAL_SAVED_AT, System.currentTimeMillis())
            }
        }

    val scanCalibrationSavedAtMs: Long get() = sp.getLong(KEY_SCAN_CAL_SAVED_AT, 0L)

    /**
     * When the patrol went to 真實位置 (PatrolPhase.SUSPENDED), 0 = it is not there. Outlives a kill, so
     * the resume dialog knows the game already sees the real GPS (ui/ResumeCopy). commit, not apply:
     * a kill right after the switch must not lose it.
     */
    var suspendedAtMs: Long
        get() = sp.getLong(KEY_SUSPENDED_AT, 0L)
        set(v) = sp.edit(commit = true) { putLong(KEY_SUSPENDED_AT, v) }

    /** The last location jump the game saw (teleport, real ⇄ virtual). Shown for a day; blocks nothing. */
    var lastJump: LocationJump?
        get() = LocationJump.fromJson(sp.getString(KEY_LAST_JUMP, null))
        set(v) = sp.edit { if (v == null) remove(KEY_LAST_JUMP) else putString(KEY_LAST_JUMP, v.toJson()) }

    // ------------------------------------------------------------------ decor hunt (ui/DecorHunt)

    /** The [Decor] picked last time, preselected in the picker. */
    var lastDecor: Decor?
        get() = sp.getString(KEY_LAST_DECOR, null)?.let { Decor.byName(it) }
        set(v) = sp.edit { if (v == null) remove(KEY_LAST_DECOR) else putString(KEY_LAST_DECOR, v.name) }

    /** How the user last chose to cover the leg to a decor place. */
    var tripTravelMode: TravelMode
        get() = enum(KEY_TRIP_TRAVEL_MODE, TravelMode.WALK)
        set(v) = sp.edit { putString(KEY_TRIP_TRAVEL_MODE, v.name) }

    /** Wander radius around the destination as typed (not clamped to the waypoint limit). */
    var tripWanderRadiusM: Double
        get() = sp.getFloat(KEY_TRIP_WANDER_RADIUS, DEFAULT_TRIP_WANDER_RADIUS_M.toFloat()).toDouble().coerceIn(1.0, 500.0)
        set(v) = sp.edit { putFloat(KEY_TRIP_WANDER_RADIUS, v.toFloat()) }

    var tripWanderMin: Int
        get() = sp.getInt(KEY_TRIP_WANDER_MIN, DEFAULT_TRIP_WANDER_MIN).coerceIn(0, 30)
        set(v) = sp.edit { putInt(KEY_TRIP_WANDER_MIN, v) }

    private fun str(key: String, default: Double): Double =
        sp.getString(key, null)?.trim()?.toDoubleOrNull() ?: default

    private inline fun <reified E : Enum<E>> enum(key: String, default: E): E {
        val raw = sp.getString(key, null) ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
    }

    companion object {
        /** 20 km/h. Above roughly this speed Pikmin Bloom stops planting flowers. */
        const val MAX_SPEED_MPS = 20.0 / 3.6

        /**
         * Walking speed until the user types one (2026-10-08, the user's call: 18 is what runs steadiest; 19-20 now and
         * then lurch forward after a thermal stall and read as a vehicle - Settings says so, the ceiling stays 20).
         */
        const val DEFAULT_WALK_KMH = 18.0

        const val KEY_SPEED_KMH = "speed_kmh"
        const val KEY_ORBIT = "orbit_at_waypoints"
        const val KEY_SPEED_CAR_KMH = "speed_car_kmh"
        const val KEY_SPEED_HIGHWAY_KMH = "speed_highway_kmh"
        const val KEY_SPEED_PLANE_KMH = "speed_plane_kmh"
        /**
         * The vehicles the user can pick as an override; BRISK/RUN are walking paces and stay fixed. The bike
         * left 2026-10-08 (its old "speed_bike_kmh" value is simply no longer read).
         */
        val VEHICLE_SPEED_KEYS: Map<TravelMode, String> = mapOf(
            TravelMode.CAR to KEY_SPEED_CAR_KMH,
            TravelMode.HIGHWAY to KEY_SPEED_HIGHWAY_KMH,
            TravelMode.PLANE to KEY_SPEED_PLANE_KMH,
        )
        const val MIN_VEHICLE_KMH = 5.0
        const val MAX_VEHICLE_KMH = 1200.0
        const val KEY_STRIDE_CM = "stride_cm"
        const val KEY_LOOP_MODE = "loop_mode"
        const val KEY_INJECT_STEPS = "inject_steps"
        const val KEY_STEP_FLUSH_SEC = "step_flush_sec"
        const val KEY_DAILY_STEP_CAP = "daily_step_cap"
        const val KEY_NOTIFY_ARRIVAL = "notify_on_arrival"
        const val KEY_VIBRATE_ARRIVAL = "vibrate_on_arrival"
        const val KEY_ALERT_GAP_SEC = "alert_gap_sec"
        const val KEY_AUTO_RETURN_LAPS = "auto_return_after_laps"
        const val KEY_MOCK_NETWORK = "mock_network"
        const val KEY_MOCK_FUSED = "mock_fused"
        const val KEY_FLP_MOCK = "flp_mock_mode"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LON = "home_lon"
        const val KEY_HOME_SAVED_AT = "home_saved_at"
        const val KEY_CUSTOM_HOME_LAT = "custom_home_lat"
        const val KEY_CUSTOM_HOME_LON = "custom_home_lon"
        const val KEY_HOMES = "custom_homes"
        const val KEY_LAST_LAT = "last_lat"
        const val KEY_LAST_LON = "last_lon"
        const val KEY_DISCLAIMER = "disclaimer_accepted"
        const val KEY_ACCURACY_ADVICE_MUTED = "accuracy_advice_muted"
        const val KEY_STAY_ASK_KM = "stay_ask_km"
        const val KEY_AUTO_NECTAR = "auto_nectar"
        const val KEY_AUTO_EXPEDITION = "auto_expedition"

        /**
         * 自動拉花 still has never collected nectar (PLAN I): debug builds only (2026-10-08, "release把它隱藏起來 除了debug版").
         * Release hides the setting, never runs it and does not even declare the accessibility service (debug manifest).
         */
        val AUTO_NECTAR_AVAILABLE: Boolean = app.pikminbloom.gps.BuildConfig.DEBUG
        const val KEY_AUTO_REAL_FOR_MAPS = "auto_real_for_maps"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_OVERLAY_PINNED = "overlay_pinned"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
        const val KEY_JOYSTICK_SIZE = "joystick_size_dp"
        const val KEY_JOYSTICK_X = "joystick_x"
        const val KEY_JOYSTICK_Y = "joystick_y"
        const val KEY_SCAN_CAL_MPP = "scan_cal_metres_per_pixel"
        const val KEY_SCAN_CAL_NORTH = "scan_cal_screen_north_deg"
        const val KEY_SCAN_CAL_SAVED_AT = "scan_cal_saved_at"
        const val KEY_SUSPENDED_AT = "suspended_at_ms"
        const val KEY_LAST_JUMP = "last_jump"
        const val KEY_LAST_DECOR = "last_decor"
        const val KEY_TRIP_TRAVEL_MODE = "trip_travel_mode"
        const val KEY_TRIP_WANDER_RADIUS = "trip_wander_radius_m"
        const val KEY_TRIP_WANDER_MIN = "trip_wander_min"

        /** Sentinel for [overlayX] / [overlayY] meaning "use the default placement". */
        const val OVERLAY_UNSET = Int.MIN_VALUE

        /** Matches the defaults of `PatrolPlanner.planTripTo` (60 m, 15 min). */
        const val DEFAULT_TRIP_WANDER_RADIUS_M = 60.0
        const val DEFAULT_TRIP_WANDER_MIN = 15
    }
}
