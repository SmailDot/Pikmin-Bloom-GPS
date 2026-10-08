package app.pikminbloom.gps.data

import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.i18n.Tr
import app.pikminbloom.gps.i18n.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Big Flower (巨大花朵) the patrol should visit.
 *
 * [radiusM] / [dwellSec] are no longer per-flower settings (retired 2026-10-03): visits are planned
 * with PatrolPlanner.CIRCLE_RADIUS_M / ORBIT_DWELL_SEC. The one exception is the first plan of a decor
 * trip (Route.travelMode != WALK, lap 0 in PatrolService.loadLap), which reads them as the wander
 * radius and time at its destination (DecorHunt's 半徑／分鐘). Later laps and every re-plan
 * (replanRemaining, goToWaypoint, leaveManual, skipWaypoint, handleResumeCheckpoint) use the constants.
 * They stay in waypoints.json so old files and trip routes load unchanged.
 */
data class Waypoint(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = DEFAULT_RADIUS_M,
    val dwellSec: Int = DEFAULT_DWELL_SEC,
) {
    val latLng: LatLng get() = LatLng(lat, lon)

    companion object {
        const val DEFAULT_RADIUS_M = 30.0
        const val DEFAULT_DWELL_SEC = 120
    }
}

/**
 * A place the user can send the avatar to with 回家 instead of the real position (家 1／家 2／…,
 * "the flat in Tokyo"). A patrol never *starts* here - starting anywhere but the real fix is a
 * teleport - it only ends here, parked with the mock still on (PatrolPhase.PARKED).
 */
data class Home(val name: String, val lat: Double, val lon: Double) {
    val latLng: LatLng get() = LatLng(lat, lon)

    companion object {
        /** "家 3" / "Home 3": the name a new home gets until the user types one. */
        fun defaultName(n: Int): String = tr("家 $n", "Home $n", "家 $n")

        fun toJson(homes: List<Home>): String = JSONArray().also { arr ->
            homes.forEach { h -> arr.put(JSONObject().put("name", h.name).put("lat", h.lat).put("lon", h.lon)) }
        }.toString()

        /** Lenient: anything that is not a well-formed entry is dropped, garbage is an empty list. */
        fun fromJson(text: String?): List<Home> {
            val arr = runCatching { JSONArray(text?.trim().orEmpty()) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (runCatching { LatLng(lat, lon) }.isFailure) return@mapNotNull null
                Home(o.optString("name"), lat, lon)
            }
        }
    }
}

enum class LoopMode { LOOP, PINGPONG, ONCE }

/**
 * How to cover a leg of a journey.
 *
 * Long legs are the problem this solves. Walking 30 km at walking speed takes six hours, and
 * "walking" it at 20 km/h is a speed no human sustains. Covering it at a vehicle speed instead is
 * both faster and more ordinary-looking, and it is honest about steps: nobody takes steps while
 * driving, so [countsSteps] is false for every vehicle and no step data is written for those legs.
 *
 * Pikmin Bloom stops planting flowers somewhere around 15-20 km/h, so vehicle legs plant nothing
 * either. That is expected: the point of a vehicle leg is to *arrive*, and the walking starts there.
 */
enum class TravelMode(
    /** Default speed; the one actually used is [PatrolConfig.speedKmhOf], which Settings can change. */
    val speedKmh: Double,
    val countsSteps: Boolean,
    private val name3: Tr,
) {
    WALK(4.7, true, Tr("步行", "Walk", "徒歩")),
    BRISK(7.0, true, Tr("快走", "Brisk walk", "早歩き")),
    RUN(10.0, true, Tr("慢跑", "Jog", "ジョギング")),
    /**
     * Retired from every picker 2026-10-08 ("腳踏車可以砍掉"). Kept so checkpoints and saved trip routes
     * that name it still load; it runs at its default speed, which Settings no longer offers to change.
     */
    BIKE(18.0, false, Tr("腳踏車", "Bike", "自転車")),
    /** "汽機車改成其他即可" (2026-10-08): the one free-speed vehicle; the enum name stays for stored data. */
    CAR(45.0, false, Tr("其他", "Other", "その他")),
    /** Named 汽車 since 2026-10-08 (was 高速公路); the enum name stays for stored data. */
    HIGHWAY(90.0, false, Tr("汽車", "Car", "車")),
    PLANE(600.0, false, Tr("飛機", "Plane", "飛行機")),
    ;

    val speedMps: Double get() = speedKmh / 3.6

    /** The name shown for this mode, in the app's language (i18n.Lang). */
    val label: String get() = name3.text

    fun labelIn(lang: Lang): String = name3.of(lang)

    companion object {
        /** What the decor trip picker offers: every mode but the retired [BIKE]. */
        val PICKABLE: List<TravelMode> get() = entries.filter { it != BIKE }

        /** Sensible mode for a leg of [distanceM], so the user does not have to think about it. */
        fun suggestFor(distanceM: Double): TravelMode = when {
            distanceM < 1_500 -> WALK
            distanceM < 15_000 -> CAR
            distanceM < 300_000 -> HIGHWAY
            else -> PLANE
        }
    }
}

/** All tunables, persisted by Prefs. Defaults are chosen to look like a normal walk (~4.7 km/h). */
data class PatrolConfig(
    val speedMps: Double = 1.3,
    val strideM: Double = 0.70,
    val loopMode: LoopMode = LoopMode.LOOP,
    /**
     * When false the patrol only walks THROUGH each Big Flower's circle and moves straight on to the
     * next one; when true it sweeps the circle for PatrolPlanner.ORBIT_DWELL_SEC. Turn it on only when you want to farm one flower's
     * 40 m circle (planting toward the 300-flower bloom).
     */
    val orbitAtWaypoints: Boolean = false,
    val injectSteps: Boolean = true,
    val stepFlushIntervalSec: Int = 60,
    /** null = no daily cap (the Settings field left empty). */
    val dailyStepCap: Long? = null,
    val notifyOnArrival: Boolean = true,
    /**
     * Buzz as well as post the arrival notification. Off by default: the vibration motor is one of
     * the few things on the phone that costs more power than the GPS work this app already does,
     * and a multi-flower loop would fire it constantly.
     */
    val vibrateOnArrival: Boolean = false,
    /** Never alert more often than this, however many flowers are passed. */
    val arrivalAlertMinGapSec: Int = 60,
    /** Walk home automatically after this many completed laps. 0 disables it. */
    val autoReturnAfterLaps: Int = 0,
    val mockNetworkProvider: Boolean = true,
    val mockFusedProvider: Boolean = true,
    val useFlpMockMode: Boolean = true,
    val tickMs: Long = 1000,
    /** Per-vehicle speeds from Settings; a mode not in the map runs at [TravelMode.speedKmh]. */
    val vehicleSpeedsKmh: Map<TravelMode, Double> = emptyMap(),
) {
    fun speedKmhOf(mode: TravelMode): Double = vehicleSpeedsKmh[mode] ?: mode.speedKmh
    fun speedMpsOf(mode: TravelMode): Double = speedKmhOf(mode) / 3.6
    /** "45" or "17.5": whole numbers drop the decimal, a typed decimal is kept. */
    fun speedTextOf(mode: TravelMode): String {
        val kmh = speedKmhOf(mode)
        return if (kmh == kmh.toLong().toDouble()) kmh.toLong().toString() else String.format(java.util.Locale.US, "%.1f", kmh)
    }
}

enum class PatrolPhase {
    IDLE,
    STARTING,        // capturing real position, enabling mock providers
    WALKING,         // moving between waypoints
    DWELLING,        // wandering inside a waypoint circle
    PAUSED,          // position frozen, mock still active
    RETURNING_HOME,  // walking (or teleporting) back to the real position
    STOPPING,        // removing mock providers
    /**
     * Parked at one of the saved homes (Prefs.homes) with the mock still on. Only 停止 hands the
     * game back to the real GPS, because a saved home is by definition not where the phone is;
     * another 回家 can move on to the real position or to a different home.
     */
    PARKED,
    /** The floating joystick steers; the route is suspended and resumes from wherever this ends. */
    MANUAL,
    /**
     * 真實位置: the mock is removed so every app (Google Maps) sees the real GPS; the patrol's virtual
     * state is kept frozen until 回到虛擬位置, which is a jump and therefore only ever a user tap.
     */
    SUSPENDED,
}

/** Snapshot published by PatrolService (StateFlow) for the UI and notification. */
data class PatrolState(
    val phase: PatrolPhase = PatrolPhase.IDLE,
    val position: LatLng? = null,
    val home: LatLng? = null,
    val currentWaypointIndex: Int = -1,
    val currentWaypointName: String? = null,
    val distanceToTargetM: Double = 0.0,
    val distanceWalkedM: Double = 0.0,
    val sessionSteps: Long = 0,
    val stepsWrittenToday: Long = 0,
    val speedMps: Double = 0.0,
    val startedAtMs: Long = 0,
    val lapsCompleted: Int = 0,
    val lastError: String? = null,
    val mockAppSelected: Boolean = false,
    val healthConnectReady: Boolean = false,
    /** Live vehicle override (PatrolService.setTravelOverride); null = the configured walking speed. */
    val travelOverride: TravelMode? = null,
    /** True while 回家 is heading to / parked at a saved home (Prefs.homes) rather than the real position. */
    val homeIsCustom: Boolean = false,
    /** 前往後停在這裡: the flower the patrol pauses at on arrival (PatrolService.stayAtId); null = none armed. */
    val stayAtName: String? = null,
)
