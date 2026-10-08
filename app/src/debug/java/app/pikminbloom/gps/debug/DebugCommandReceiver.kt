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
                val dwell = intent.getIntExtra("dwell", Waypoint.DEFAULT_DWELL_SEC)
                val radius = intent.getDoubleExtra("radius", Waypoint.DEFAULT_RADIUS_M)
                val list = (intent.getStringExtra("waypoints") ?: "").split(';').mapNotNull { item ->
                    val parts = item.split(',')
                    val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    Waypoint(UUID.randomUUID().toString(), parts.getOrNull(2)?.trim().orEmpty().ifBlank { "大花" }, lat, lon, radius, dwell)
                }
                store.save(list)
                // Optional: --es travel_mode CAR makes the active route a "trip" (drive to the first
                // place, wander it on foot, drive back).
                intent.getStringExtra("travel_mode")?.let { name ->
                    runCatching { app.pikminbloom.gps.data.TravelMode.valueOf(name.uppercase()) }.getOrNull()?.let { mode ->
                        store.setRouteTravelMode(store.activeRouteId.value, mode)
                        Log.i(TAG, "route travel mode: $mode")
                    }
                }
                Log.i(TAG, "waypoints set: ${list.size}")
            }
            "start" -> {
                val h = parseLatLng(intent.getStringExtra("home")) ?: prefs.home
                PatrolService.start(context, h, intent.getIntExtra("start_index", 0), intent.getBooleanExtra("teleport", false))
                Log.i(TAG, "start requested, home=$h")
            }
            "resume_checkpoint" -> {
                val goHome = intent.getBooleanExtra("go_home", false)
                val cp = app.pikminbloom.gps.service.PatrolCheckpoint.resumable(context)
                Log.i(TAG, "checkpoint: ${cp?.toJson() ?: "none"}")
                PatrolService.resumeFromCheckpoint(context, goHome)
            }
            "checkpoint" -> Log.i(TAG, "CHECKPOINT ${app.pikminbloom.gps.service.PatrolCheckpoint.load(context)?.toJson() ?: "none"}")
            "pause" -> PatrolService.pause(context)
            "resume" -> PatrolService.resume(context)
            // return_home [--es home "lat,lon"] [--ez teleport true]: no home = the real position, else park at that saved home.
            "return_home" -> PatrolService.returnHome(context, parseLatLng(intent.getStringExtra("home")), intent.getBooleanExtra("teleport", false))
            "stop" -> PatrolService.stop(context)
            // real / virtual: 真實位置 ⇄ 回到虛擬位置 (PatrolPhase.SUSPENDED).
            "real" -> PatrolService.switchToReal(context)
            "virtual" -> PatrolService.switchToVirtual(context)
            "skip" -> PatrolService.skipWaypoint(context)
            // Live controls added 2026-09-12.
            // goto --ei index N [--ez teleport true]
            "goto" -> PatrolService.goTo(context, intent.getIntExtra("index", 0), intent.getBooleanExtra("teleport", false))
            "travel" -> {
                // --es mode CAR | BIKE | HIGHWAY | PLANE | WALK (WALK = back to the configured speed)
                val name = intent.getStringExtra("mode").orEmpty().uppercase()
                val mode = if (name == "WALK" || name.isBlank()) null
                else runCatching { app.pikminbloom.gps.data.TravelMode.valueOf(name) }.getOrNull()
                PatrolService.setTravelOverride(mode)
                Log.i(TAG, "travel override -> ${mode ?: "walk"}")
            }
            "joystick" -> {
                // --ez on true --ef bearing 90 --ef magnitude 1 [--ez cruise true]
                val on = intent.getBooleanExtra("on", true)
                PatrolService.setJoystickEnabled(on)
                if (on) {
                    PatrolService.steer(intent.getFloatExtra("bearing", 0f).toDouble(), intent.getFloatExtra("magnitude", 1f).toDouble())
                    if (intent.hasExtra("cruise")) PatrolService.setCruise(intent.getBooleanExtra("cruise", false))
                }
                Log.i(TAG, "joystick -> ${PatrolService.joystick.value}")
            }
            // add_home --es home "lat,lon" [--es name "..."]; clear_homes
            "add_home" -> {
                val h = parseLatLng(intent.getStringExtra("home")) ?: run { Log.w(TAG, "add_home: bad home"); return }
                val list = prefs.homes
                val name = intent.getStringExtra("name") ?: "家 ${list.size + 1}"
                prefs.homes = list + app.pikminbloom.gps.data.Home(name, h.lat, h.lon)
                Log.i(TAG, "homes -> ${prefs.homes}")
            }
            "clear_homes" -> { prefs.homes = emptyList(); Log.i(TAG, "homes cleared") }
            // collect_nectar: run 自動拉花 on the current screen right now (capture + accessibility service must be on).
            "auto_nectar" -> { prefs.autoNectar = intent.getBooleanExtra("on", false); Log.i(TAG, "auto_nectar -> ${prefs.autoNectar}") }
            // auto_real_maps --ez on true: 開 Google 地圖時自動切到真實位置 (also needs 使用情況存取權 in system settings).
            "auto_real_maps" -> { prefs.autoRealForMaps = intent.getBooleanExtra("on", false); Log.i(TAG, "auto_real_for_maps -> ${prefs.autoRealForMaps}") }
            "collect_nectar" -> app.pikminbloom.gps.nectar.NectarRunner.collectNow(context) { Log.i(TAG, "NECTAR $it") }
            "add_waypoint" -> {
                // Mid-patrol edit test: --es waypoint "lat,lon,name" (appended to the active route)
                val parts = (intent.getStringExtra("waypoint") ?: "").split(',')
                val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    store.add(Waypoint(UUID.randomUUID().toString(), parts.getOrNull(2)?.trim().orEmpty().ifBlank { "大花" }, lat, lon))
                    Log.i(TAG, "waypoint added, now ${store.load().size}")
                }
            }
            "remove_waypoint" -> {
                val idx = intent.getIntExtra("index", -1)
                store.load().getOrNull(idx)?.let { store.remove(it.id); Log.i(TAG, "waypoint $idx removed, now ${store.load().size}") }
            }
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
                    .put("travelOverride", s.travelOverride?.name)
                    .put("homeIsCustom", s.homeIsCustom)
                    .put("joystick", PatrolService.joystick.value.toString())
                    .put("homes", prefs.homes.joinToString { "${it.name}@${it.latLng}" })
                    .put("lastJump", prefs.lastJump?.toJson())
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

    private fun parseLatLng(s: String?): LatLng? = LatLng.parse(s)

    companion object {
        private const val TAG = "PikminGPS"
    }
}
