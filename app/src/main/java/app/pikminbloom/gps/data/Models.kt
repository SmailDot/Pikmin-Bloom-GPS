package app.pikminbloom.gps.data

import app.pikminbloom.gps.geo.LatLng

/**
 * A Big Flower (巨大花朵) the patrol should visit.
 *
 * @param radiusM  radius of the circle the walker wanders inside once it arrives (Pikmin Bloom counts
 *                 flowers planted within 40 m of a Big Flower; default 30 m keeps us safely inside).
 * @param dwellSec seconds to keep wandering inside the circle before moving on (0 = pass through only).
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

enum class LoopMode { LOOP, PINGPONG, ONCE }

enum class ReturnMode { WALK, TELEPORT }

/** All tunables, persisted by Prefs. Defaults are chosen to look like a normal walk (~4.7 km/h). */
data class PatrolConfig(
    val speedMps: Double = 1.3,
    val speedJitterPct: Double = 10.0,
    val strideM: Double = 0.70,
    val loopMode: LoopMode = LoopMode.LOOP,
    val injectSteps: Boolean = true,
    val stepFlushIntervalSec: Int = 60,
    val dailyStepCap: Long = 50_000,
    val accuracyMinM: Float = 3f,
    val accuracyMaxM: Float = 9f,
    val altitudeM: Double = 20.0,
    val notifyOnArrival: Boolean = true,
    val returnMode: ReturnMode = ReturnMode.WALK,
    val mockNetworkProvider: Boolean = true,
    val mockFusedProvider: Boolean = true,
    val useFlpMockMode: Boolean = true,
    val tickMs: Long = 1000,
)

enum class PatrolPhase {
    IDLE,
    STARTING,        // capturing real position, enabling mock providers
    WALKING,         // moving between waypoints
    DWELLING,        // wandering inside a waypoint circle
    PAUSED,          // position frozen, mock still active
    RETURNING_HOME,  // walking (or teleporting) back to the real position
    STOPPING,        // removing mock providers
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
)
