package app.pikminbloom.gps.service

import android.content.Context
import android.util.Log
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.TravelMode
import java.time.LocalDate
import app.pikminbloom.gps.geo.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where a patrol was a few seconds ago, written continuously so that a process death (thermal
 * kill, low memory, a crash) can be resumed from the point it stopped.
 *
 * Why this exists: when the process dies the test providers stay registered and the game keeps
 * showing the last mocked position. A plain restart would take a fresh real fix as home and walk
 * the route from the beginning, which the game sees as a teleport from the crash point back to the
 * real position and then off along the route. Resuming from the checkpoint instead re-pushes the
 * very same position first, so nothing jumps.
 *
 * Written atomically (temp file + rename) at most every few seconds on the engine thread; deleted
 * on every clean exit.
 */
data class PatrolCheckpoint(
    val savedAtMs: Long,
    val startedAtMs: Long,
    val home: LatLng,
    val position: LatLng,
    val routeId: String,
    val lap: Int,
    /** Index of the waypoint being walked to or orbited when the checkpoint was taken. */
    val targetWaypointIndex: Int,
    val phase: PatrolPhase,
    val distanceWalkedM: Double,
    val stepsAccrued: Double,
    val stepsFlushed: Long,
    val flushWindowStartMs: Long,
    val distanceSinceFlush: Double,
    val stepsWrittenToday: Long,
    val lapsCompleted: Int,
    /** Where 回家 was heading when this was taken: a chosen home to park at, or null = the real [home]. */
    val returnTarget: LatLng? = null,
    /** Vehicle in force (移動方式); null = walking. Without it a resume mid-flight would walk. */
    val travelOverride: TravelMode? = null,
    /** The local day [stepsWrittenToday] belongs to, so a resume after midnight starts a fresh count. */
    val ledgerDay: LocalDate? = null,
    /** Waypoint ids already visited this lap, so a resume does not walk them again. */
    val doneIds: List<String> = emptyList(),
    /** 前往後停在這裡 armed for this waypoint id: an overnight trip killed on the way still stops there (2026-10-08). */
    val stayAtId: String? = null,
) {
    val ageMs: Long get() = System.currentTimeMillis() - savedAtMs

    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("savedAtMs", savedAtMs)
        .put("startedAtMs", startedAtMs)
        .put("homeLat", home.lat).put("homeLon", home.lon)
        .put("posLat", position.lat).put("posLon", position.lon)
        .put("routeId", routeId)
        .put("lap", lap)
        .put("targetWaypointIndex", targetWaypointIndex)
        .put("phase", phase.name)
        .put("distanceWalkedM", distanceWalkedM)
        .put("stepsAccrued", stepsAccrued)
        .put("stepsFlushed", stepsFlushed)
        .put("flushWindowStartMs", flushWindowStartMs)
        .put("distanceSinceFlush", distanceSinceFlush)
        .put("stepsWrittenToday", stepsWrittenToday)
        .put("lapsCompleted", lapsCompleted)
        .apply { returnTarget?.let { put("returnLat", it.lat).put("returnLon", it.lon) } }
        .apply { travelOverride?.let { put("travelOverride", it.name) } }
        .apply { ledgerDay?.let { put("ledgerDay", it.toString()) } }
        .apply { if (doneIds.isNotEmpty()) put("doneIds", JSONArray(doneIds)) }
        .apply { stayAtId?.let { put("stayAtId", it) } }
        .toString()

    companion object {
        private const val TAG = "PikminGPS"
        private const val FILE_NAME = "patrol_checkpoint.json"


        fun fromJson(text: String): PatrolCheckpoint? = runCatching {
            val o = JSONObject(text)
            PatrolCheckpoint(
                savedAtMs = o.getLong("savedAtMs"),
                startedAtMs = o.optLong("startedAtMs", o.getLong("savedAtMs")),
                home = LatLng(o.getDouble("homeLat"), o.getDouble("homeLon")),
                position = LatLng(o.getDouble("posLat"), o.getDouble("posLon")),
                routeId = o.optString("routeId"),
                lap = o.optInt("lap", 0),
                targetWaypointIndex = o.optInt("targetWaypointIndex", 0),
                phase = runCatching { PatrolPhase.valueOf(o.optString("phase")) }.getOrDefault(PatrolPhase.WALKING),
                distanceWalkedM = o.optDouble("distanceWalkedM", 0.0),
                stepsAccrued = o.optDouble("stepsAccrued", 0.0),
                stepsFlushed = o.optLong("stepsFlushed", 0L),
                flushWindowStartMs = o.optLong("flushWindowStartMs", o.getLong("savedAtMs")),
                distanceSinceFlush = o.optDouble("distanceSinceFlush", 0.0),
                stepsWrittenToday = o.optLong("stepsWrittenToday", 0L),
                lapsCompleted = o.optInt("lapsCompleted", 0),
                returnTarget = if (o.has("returnLat") && o.has("returnLon")) LatLng(o.getDouble("returnLat"), o.getDouble("returnLon")) else null,
                travelOverride = o.optString("travelOverride").takeIf { it.isNotBlank() }?.let { n -> runCatching { TravelMode.valueOf(n) }.getOrNull() },
                ledgerDay = o.optString("ledgerDay").takeIf { it.isNotBlank() }?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() },
                doneIds = o.optJSONArray("doneIds")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } } ?: emptyList(),
                stayAtId = o.optString("stayAtId").takeIf { it.isNotBlank() },
            )
        }.getOrNull()

        private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

        fun load(context: Context): PatrolCheckpoint? {
            val f = file(context)
            if (!f.exists()) return null
            return runCatching { fromJson(f.readText()) }
                .onFailure { Log.w(TAG, "checkpoint unreadable", it) }
                .getOrNull()
        }

        /** Atomic: a kill halfway through a write must not leave a truncated file behind. */
        fun save(context: Context, cp: PatrolCheckpoint) {
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            runCatching {
                tmp.writeText(cp.toJson())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }.onFailure { Log.w(TAG, "checkpoint save failed", it) }
        }

        fun clear(context: Context) {
            runCatching { file(context).delete(); File(file(context).parentFile, "$FILE_NAME.tmp").delete() }
        }

        /**
         * A checkpoint exists, whatever its age. There used to be a 12 h limit, after which the app
         * start silently removed the mock providers - i.e. a parked avatar (a saved home far away, days long)
         * teleported back to the real GPS the moment the app was opened. The dialog shows the age
         * and offers 放棄; that decision is the user's, never the clock's.
         */
        fun resumable(context: Context): PatrolCheckpoint? = load(context)
    }
}
