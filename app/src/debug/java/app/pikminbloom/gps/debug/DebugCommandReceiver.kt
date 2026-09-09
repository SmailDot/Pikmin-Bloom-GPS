package app.pikminbloom.gps.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.steps.StepInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Debug-only adb driver. Examples:
 *   am broadcast -a app.pikminbloom.gps.DEBUG_CMD --es cmd set_home --es home "25.0330,121.5654"
 *   am broadcast -a app.pikminbloom.gps.DEBUG_CMD --es cmd set_waypoints --es waypoints "25.0341,121.5654,花A;25.0350,121.5660,花B" --ei dwell 60
 *   am broadcast -a app.pikminbloom.gps.DEBUG_CMD --es cmd start
 *   am broadcast -a app.pikminbloom.gps.DEBUG_CMD --es cmd status
 *   am broadcast -a app.pikminbloom.gps.DEBUG_CMD --es cmd write_steps --ei steps 37 --ei minutes 2
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: run { Log.w(TAG, "DEBUG_CMD without cmd"); return }
        Log.i(TAG, "DEBUG_CMD $cmd ${intent.extras?.keySet()?.joinToString()}")
        val prefs = Prefs(context)
        val store = WaypointStore.get(context)
        when (cmd) {
            "set_home" -> {
                val h = parseLatLng(intent.getStringExtra("home"))
                prefs.home = h
                Log.i(TAG, "home set to $h")
            }
            "clear_home" -> { prefs.home = null; Log.i(TAG, "home cleared") }
            "set_waypoints" -> {
                val dwell = intent.getIntExtra("dwell", prefs.defaultDwellSec)
                val radius = intent.getDoubleExtra("radius", prefs.defaultRadiusM)
                val list = (intent.getStringExtra("waypoints") ?: "").split(';').mapNotNull { item ->
                    val parts = item.split(',')
                    val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    Waypoint(UUID.randomUUID().toString(), parts.getOrNull(2)?.trim().orEmpty().ifBlank { "大花" }, lat, lon, radius, dwell)
                }
                store.save(list)
                Log.i(TAG, "waypoints set: ${list.size}")
            }
            "start" -> {
                val h = parseLatLng(intent.getStringExtra("home")) ?: prefs.home
                PatrolService.start(context, h, intent.getIntExtra("start_index", 0))
                Log.i(TAG, "start requested, home=$h")
            }
            "pause" -> PatrolService.pause(context)
            "resume" -> PatrolService.resume(context)
            "return_home" -> PatrolService.returnHome(context)
            "stop" -> PatrolService.stop(context)
            "skip" -> PatrolService.skipWaypoint(context)
            "status" -> {
                val s = PatrolService.state.value
                val mock = MockLocationController(context)
                val json = JSONObject()
                    .put("phase", s.phase.name)
                    .put("position", s.position?.toString())
                    .put("home", s.home?.toString() ?: prefs.home?.toString())
                    .put("waypoint", s.currentWaypointName)
                    .put("waypointIndex", s.currentWaypointIndex)
                    .put("distanceToTargetM", s.distanceToTargetM)
                    .put("distanceWalkedM", s.distanceWalkedM)
                    .put("sessionSteps", s.sessionSteps)
                    .put("stepsWrittenToday", s.stepsWrittenToday)
                    .put("speedMps", s.speedMps)
                    .put("laps", s.lapsCompleted)
                    .put("lastError", s.lastError)
                    .put("mockAppSelected", mock.isMockAppSelected())
                    .put("waypoints", store.load().size)
                    .put("config", prefs.config().toString())
                Log.i(TAG, "STATUS $json")
            }
            "write_steps", "steps_today", "delete_steps_today" -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val inj = StepInjector(context)
                        Log.i(TAG, "HC available=${inj.isAvailable} status=${inj.sdkStatus} granted=${inj.grantedPermissions()}")
                        when (cmd) {
                            "write_steps" -> {
                                val n = intent.getIntExtra("steps", 30).toLong()
                                val minutes = intent.getIntExtra("minutes", 2).toLong()
                                val end = Instant.now()
                                val ok = inj.write(end.minusSeconds(minutes * 60), end, n, n * 0.7)
                                Log.i(TAG, "write_steps $n over ${minutes}min -> $ok")
                            }
                            "delete_steps_today" -> Log.i(TAG, "delete -> ${inj.deleteOurRecordsToday()}")
                        }
                        Log.i(TAG, "STEPS today(all)=${inj.stepsToday()} ours=${inj.stepsWrittenByUsToday()}")
                    } catch (t: Throwable) {
                        Log.e(TAG, "HC command failed", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> Log.w(TAG, "unknown cmd $cmd")
        }
    }

    private fun parseLatLng(s: String?): LatLng? {
        val parts = s?.split(',') ?: return null
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        return runCatching { LatLng(lat, lon) }.getOrNull()
    }

    companion object {
        private const val TAG = "PikminGPS"
    }
}
