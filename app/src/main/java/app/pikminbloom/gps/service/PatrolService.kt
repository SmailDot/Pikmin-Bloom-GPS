package app.pikminbloom.gps.service

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.LocationJump
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.mock.MockNotAllowedException
import app.pikminbloom.gps.nectar.NectarResult
import app.pikminbloom.gps.nectar.NectarRunner
import app.pikminbloom.gps.route.PatrolPlan
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.route.RouteSegment
import app.pikminbloom.gps.route.SegmentKind
import app.pikminbloom.gps.sim.JoystickInput
import app.pikminbloom.gps.sim.Sample
import app.pikminbloom.gps.sim.WalkSimulator
import app.pikminbloom.gps.steps.DailyLedger
import app.pikminbloom.gps.steps.StepInjector
import app.pikminbloom.gps.ui.MapsPrompt
import app.pikminbloom.gps.ui.ResumeCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * Foreground service that runs the whole patrol: captures the real position ("home"), installs the
 * mock providers, ticks the [WalkSimulator] once a second, flushes steps to Health Connect and walks
 * back home on request.
 *
 * Everything the user can change while walking is honoured live: settings (speed), the waypoint
 * list (re-planned from where we are), a vehicle override ([setTravelOverride]) and the floating
 * joystick ([joystick]).
 *
 * Threading: everything that touches the simulator, the plan or the step accounting runs on the
 * single-threaded [engine] dispatcher. Control actions arriving on the main thread are posted there.
 */
class PatrolService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var store: WaypointStore
    private lateinit var mock: MockLocationController
    private lateinit var steps: StepInjector
    private lateinit var notifications: PatrolNotifications

    private val engine = Executors.newSingleThreadExecutor { r -> Thread(r, "PikminGPS-engine") }.asCoroutineDispatcher()

    private var config = PatrolConfig()
    private var waypoints: List<Waypoint> = emptyList()
    private var sim = WalkSimulator(config)
    private var plan: PatrolPlan = PatrolPlan.EMPTY
    private var lap = 0
    /** The real fix the patrol started from; 回家 without a destination walks back here and releases the mock. */
    private var home: LatLng? = null

    /** Where the current 回家 is heading: [home], or one of the user's saved homes (Prefs.homes). */
    private var returnTarget: LatLng? = null

    /** True when [returnTarget] is a saved home: arriving parks there instead of releasing the mock. */
    private var parkAtHome = false

    /** This 回家 jumps instead of walking - picked per use, never by a setting (2026-10-03). */
    private var returnTeleport = false

    /** Ids of the waypoints already visited (or skipped) in the current lap; a re-plan leaves them out. */
    private val doneThisLap = HashSet<String>()

    /** A waypoint edit arrived mid-vehicle-leg / mid-orbit; apply it once that leg is over. */
    private var replanPending = false

    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTickElapsedMs = 0L
    private var lastNotificationMs = 0L
    /** What the ongoing notification said when [lastNotificationMs] posted it (PatrolNotifications.ongoingKey); null = unknown. */
    private var lastNotificationKey: String? = null

    // Step accounting (engine thread only).
    private var stepsAccrued = 0.0
    private var stepsFlushed = 0L
    private var distanceSinceFlush = 0.0
    private var flushWindowStart: Instant = Instant.now()
    /** 今日已寫入步數 with its day, so a patrol across midnight starts a fresh count (DailyLedger). */
    private var ledger = DailyLedger(java.time.LocalDate.now(), 0L)
    private val stepsWrittenToday: Long get() = ledger.total

    /** What Health Connect holds for us today, or 0 when it cannot be read. */
    private suspend fun stepsHeldToday(): Long =
        if (steps.isAvailable) runCatching { steps.stepsWrittenByUsToday() }.getOrDefault(0L) else 0L

    /**
     * Restart 今日已寫入步數 from Health Connect: at start, on a resume, when 寫入步數 is switched on
     * mid-patrol, and after 重設今日寫入的步數 in Settings (which deletes the records but knew
     * nothing about this counter, so the daily cap stayed shut - 2026-09-14).
     */
    private suspend fun reseedLedger(reason: String) {
        ledger = DailyLedger(java.time.LocalDate.now(), stepsHeldToday())
        _state.update { it.copy(stepsWrittenToday = ledger.total) }
        Log.i(TAG, "今日已寫入步數 reseeded ($reason): ${ledger.total} on ${ledger.day}")
    }

    /** A new local day: the cap opens again and the counter restarts from what Health Connect holds. */
    private suspend fun rollLedgerIfNewDay() {
        if (ledger.day == java.time.LocalDate.now()) return
        if (ledger.rollTo(java.time.LocalDate.now(), freshTotal = stepsHeldToday())) {
            Log.i(TAG, "new day ${ledger.day}: 今日已寫入步數 restarts at ${ledger.total}")
            _state.update { it.copy(stepsWrittenToday = ledger.total) }
        }
    }

    /** 立刻前往 / 瞬移 / 前往後停在這裡 tapped while STARTING (the GPS fix can take 20 s): honoured once the fix is in. */
    private data class GoTo(val index: Int, val teleport: Boolean, val stay: Boolean)
    @Volatile private var pendingGoTo: GoTo? = null
    private var settleTicks = 0
    private var lastArrivalAlertMs = 0L
    private var lastCheckpointMs = 0L

    /** Set (on the engine thread) to make the tick loop exit and run the matching finish sequence. */
    private sealed class Finish {
        data object Stop : Finish()
        data object ReturnedHome : Finish()
        data class Failed(val message: String) : Finish()
    }
    @Volatile private var pendingFinish: Finish? = null
    @Volatile private var stopRequested = false

    /** Strong reference: SharedPreferences only holds listeners weakly. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in LIVE_CONFIG_KEYS) lifecycleScope.launch(engine) { onConfigChanged(key) }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = WaypointStore.get(this)
        mock = MockLocationController(this)
        steps = StepInjector(this)
        notifications = PatrolNotifications(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        // Every non-mock fix the system hands out while the mock is on (MockGuard decides on the engine thread).
        mock.onForeignFix = { provider, fix, accuracyM -> lifecycleScope.launch(engine) { onForeignFix(provider, fix, accuracyM) } }

        // Live inputs. Each collector runs on the engine thread, so it can touch the simulator.
        lifecycleScope.launch(engine) { store.waypoints.collect { onWaypointsChanged(it) } }
        lifecycleScope.launch(engine) { _travelOverride.collect { onTravelOverrideChanged(it) } }
        lifecycleScope.launch(engine) {
            _joystick.map { it.enabled }.distinctUntilChanged().collect { onJoystickToggled(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action == ACTION_START) {
            handleStart(intent)
            return START_NOT_STICKY
        }
        if (action == ACTION_RESUME_CHECKPOINT) {
            handleResumeCheckpoint(intent.getBooleanExtra(EXTRA_THEN_RETURN_HOME, false))
            return START_NOT_STICKY
        }
        if (state.value.phase == PatrolPhase.IDLE) {
            // Stale notification action after the process died. It arrived via
            // startForegroundService(), and stopping before startForeground() is NOT a discharge:
            // AOSP bringDownServiceLocked() crashes the process with
            // ForegroundServiceDidNotStartInTimeException (seen on Android 16, 2026-09-12).
            // So go foreground for one instant, then leave.
            if (goForeground()) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_PAUSE -> lifecycleScope.launch(engine) { setPaused(true) }
            ACTION_RESUME -> lifecycleScope.launch(engine) { setPaused(false) }
            ACTION_RETURN_HOME -> {
                val dest = if (intent.hasExtra(EXTRA_HOME_LAT) && intent.hasExtra(EXTRA_HOME_LON)) {
                    runCatching { LatLng(intent.getDoubleExtra(EXTRA_HOME_LAT, 0.0), intent.getDoubleExtra(EXTRA_HOME_LON, 0.0)) }.getOrNull()
                } else null
                val teleport = intent.getBooleanExtra(EXTRA_TELEPORT, false)
                lifecycleScope.launch(engine) { beginReturnHome(dest, teleport) }
            }
            // The notification has no room for a picker: it goes wherever the last 回家 went (the
            // saved home when roaming from one), never straight to the real GPS by accident.
            ACTION_RETURN_HOME_DEFAULT -> lifecycleScope.launch(engine) { beginReturnHome() }
            ACTION_RESEED_STEPS -> lifecycleScope.launch(engine) { reseedLedger("settings") }
            ACTION_SWITCH_TO_REAL -> lifecycleScope.launch(engine) { enterRealMode("user") }
            ACTION_SWITCH_TO_VIRTUAL -> lifecycleScope.launch(engine) { leaveRealMode() }
            ACTION_STOP -> lifecycleScope.launch(engine) { requestStop() }
            ACTION_SKIP_WAYPOINT -> lifecycleScope.launch(engine) { skipWaypoint() }
            ACTION_GO_TO -> {
                val index = intent.getIntExtra(EXTRA_WAYPOINT_INDEX, -1)
                val teleport = intent.getBooleanExtra(EXTRA_TELEPORT, false)
                val stay = intent.getBooleanExtra(EXTRA_STAY, false)
                lifecycleScope.launch(engine) { goToWaypoint(index, teleport, stay) }
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ start

    private fun handleStart(intent: Intent) {
        // Location permission must exist before startForeground(type = location) on API 34+.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(lastError = getString(R.string.svc_err_no_location_permission)) }
            _events.tryEmit(PatrolEvent.Error(getString(R.string.svc_err_no_location_permission)))
            notifications.error(getString(R.string.svc_err_no_location_permission))
            stopSelf()
            return
        }
        // Always satisfy startForegroundService(), even for a duplicate start.
        if (!goForeground()) return
        if (state.value.phase != PatrolPhase.IDLE) {
            Log.i(TAG, "start ignored: already ${state.value.phase}")
            return
        }
        _state.value = PatrolState(phase = PatrolPhase.STARTING, mockAppSelected = mock.isMockAppSelected(), travelOverride = _travelOverride.value)
        stopRequested = false
        pendingFinish = null

        // A fresh start removes the stale providers and takes a real fix, which is exactly the
        // crash-point -> real-position teleport a checkpoint exists to prevent. Refuse until the
        // user has chosen resume / go home / discard in the UI.
        if (PatrolCheckpoint.resumable(this) != null) { failNow(getString(R.string.svc_err_checkpoint_pending)); return }

        config = prefs.config()
        waypoints = store.load()
        if (waypoints.isEmpty()) { failNow(getString(R.string.svc_err_no_waypoints)); return }
        if (!mock.isMockAppSelected()) { failNow(getString(R.string.svc_err_not_mock_app)); return }

        val overrideHome = if (intent.hasExtra(EXTRA_HOME_LAT) && intent.hasExtra(EXTRA_HOME_LON)) {
            runCatching { LatLng(intent.getDoubleExtra(EXTRA_HOME_LAT, 0.0), intent.getDoubleExtra(EXTRA_HOME_LON, 0.0)) }.getOrNull()
        } else null
        // Home is ALWAYS where the phone really is (or the debug driver's override). Starting from
        // a saved home would be a teleport from the real position - the one thing this app must
        // never do. Saved homes are destinations for 回家 only (see beginReturnHome).
        val startIndex = intent.getIntExtra(EXTRA_START_AT_INDEX, 0).coerceIn(0, waypoints.size - 1)
        // 瞬移 (PLAN K): the user picked it for this one start. Home stays the real fix; only the
        // first pushed position is the flower's circle edge instead of home.
        val teleport = intent.getBooleanExtra(EXTRA_TELEPORT, false)
        val stay = intent.getBooleanExtra(EXTRA_STAY, false)
        acquireWakeLock()

        lifecycleScope.launch(engine) {
            try {
                // A crashed/killed previous run may have left test providers installed; they would
                // make every "real" fix look mocked, so clear them before capturing home.
                mock.stop()
                val h = overrideHome ?: mock.currentRealLocation(20_000)
                if (stopRequested) { failNow(getString(R.string.svc_phase_stopping), silent = true); return@launch }
                if (h == null) { failNow(getString(R.string.svc_err_no_home)); return@launch }
                home = h
                returnTarget = null
                parkAtHome = false
                returnTeleport = false
                prefs.home = h
                ledger = DailyLedger(java.time.LocalDate.now(), stepsHeldToday())
                if (stopRequested) { failNow(getString(R.string.svc_phase_stopping), silent = true); return@launch }
                mock.start(config)   // throws MockNotAllowedException
                sim = WalkSimulator(config).also { it.travelOverride = _travelOverride.value }
                lap = 0
                stepsAccrued = 0.0; stepsFlushed = 0; distanceSinceFlush = 0.0
                flushWindowStart = Instant.now()
                // A flower tapped while we were still fixing: that is where the user wants to start.
                val (goIndex, goTeleport, goStay) = pendingGoTo?.also { pendingGoTo = null }?.takeIf { it.index in waypoints.indices } ?: GoTo(startIndex, teleport, stay)
                val startAt = if (goTeleport) PatrolPlanner.teleportEntry(waypoints[goIndex], h) else h
                loadLap(startAt, goIndex)
                if (goTeleport) {
                    Log.i(TAG, "teleported to ${waypoints[goIndex].name} at $startAt (manual)")
                    recordJump(h, startAt, "teleport start")   // h = the real fix the game saw until now
                    teleportTargetId = waypoints[goIndex].id
                }
                stayAtId = if (goStay && !goTeleport) waypoints[goIndex].id else null
                _state.update {
                    it.copy(
                        phase = if (plan.isEmpty) PatrolPhase.WALKING else phaseFor(plan.segments[0].kind),
                        home = h, position = startAt, startedAtMs = System.currentTimeMillis(),
                        stepsWrittenToday = stepsWrittenToday, mockAppSelected = true,
                        healthConnectReady = steps.isAvailable, lastError = null,
                        currentWaypointIndex = plan.segments.firstOrNull()?.waypointIndex ?: -1,
                        currentWaypointName = plan.segments.firstOrNull()?.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name },
                        homeIsCustom = false,
                    )
                }
                Log.i(TAG, "started at $h (override: ${_travelOverride.value})")
                health("START${if (goTeleport) " with a 瞬移" else ""} (${MockHealthLog.zoneNote()})")
                lastNotificationMs = 0
                // The joystick may already be up (toggled before 開始); take it into account now.
                if (_joystick.value.enabled) enterManual()
                startTicking()
            } catch (e: MockNotAllowedException) {
                failNow(e.message ?: getString(R.string.svc_err_not_mock_app))
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                failNow(t.message ?: "start failed")
            }
        }
    }

    // ------------------------------------------------------------------ resume after a process death

    /**
     * Picks a patrol up from its [PatrolCheckpoint] after the process was killed (thermal, OOM,
     * crash). The game is still parked at the checkpoint position, so the first thing done after
     * installing the providers is to push that exact position again: nothing on screen moves.
     *
     * Home is the checkpoint's home, not a fresh fix. A fresh fix is impossible here anyway (the
     * stale providers are still masking the real GPS and removing them first is the teleport we
     * are avoiding), and a kill happens minutes into a session, not hours, so the user has not
     * moved. If they have, they can discard the checkpoint and start fresh instead.
     *
     * A patrol that was PAUSED comes back PAUSED at that position ([ResumeCopy.restoresPaused]), not walking: 繼續 walks it on.
     */
    private fun handleResumeCheckpoint(thenReturnHome: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _events.tryEmit(PatrolEvent.Error(getString(R.string.svc_err_no_location_permission)))
            stopSelf(); return
        }
        if (!goForeground()) return
        if (state.value.phase != PatrolPhase.IDLE) { Log.i(TAG, "resume ignored: already ${state.value.phase}"); return }
        val cp = PatrolCheckpoint.resumable(this) ?: run { failNow(getString(R.string.svc_err_no_checkpoint)); return }

        _state.value = PatrolState(phase = PatrolPhase.STARTING, mockAppSelected = mock.isMockAppSelected(), travelOverride = _travelOverride.value)
        stopRequested = false
        pendingFinish = null
        config = prefs.config()
        if (cp.routeId.isNotBlank()) store.switchTo(cp.routeId)
        waypoints = store.load()
        if (!mock.isMockAppSelected()) { failNow(getString(R.string.svc_err_not_mock_app)); return }
        acquireWakeLock()

        lifecycleScope.launch(engine) {
            try {
                // The checkpoint is up to a few seconds behind where the last fix actually left the
                // player. If the system still holds that fix, resume from it and nothing moves at all.
                val parked = mock.lastParkedMockFix()
                    ?.takeIf { GeoMath.distanceM(it, cp.position) <= 100.0 }
                val resumeAt = parked ?: cp.position
                if (parked != null) Log.i(TAG, "resuming from the parked fix $parked (checkpoint was ${cp.position})")

                // Replace the stale providers in place and immediately re-assert the parked position.
                mock.start(config, keepExisting = true)
                mock.pushRaw(resumeAt, accuracyM = 5f)
                // A kill on 真實位置 (the resume dialog's own rule, so it says "resuming is the jump" exactly when this records one):
                // the game saw the real GPS until the push above, from either 繼續 or 走回家.
                if (ResumeCopy.useRealModeText(prefs.suspendedAtMs, cp.savedAtMs)) recordJump(cp.home, resumeAt, "resume from real")
                prefs.suspendedAtMs = 0L    // the game is on the patrol's position again (a jump if it died on 真實位置)

                home = cp.home
                // A 回家 to a saved home that was cut short continues to that home; a kill while
                // PARKED re-parks where the avatar already stands (no walk, no jump).
                returnTarget = if (cp.phase == PatrolPhase.PARKED) resumeAt else cp.returnTarget
                parkAtHome = cp.phase == PatrolPhase.PARKED || cp.returnTarget != null
                returnTeleport = false      // a jump is picked per use and never checkpointed: a resumed return walks
                prefs.home = cp.home
                // The vehicle was process-local: without this a kill mid-flight resumed on foot.
                _travelOverride.value = cp.travelOverride
                sim = WalkSimulator(config).also { it.travelOverride = cp.travelOverride }
                lap = cp.lap
                stepsAccrued = cp.stepsAccrued
                stepsFlushed = cp.stepsFlushed
                distanceSinceFlush = cp.distanceSinceFlush
                // Steps pending from before the kill are written with the window they were walked
                // in, not spread over the dead time; a very old window is cut to one flush interval.
                flushWindowStart = maxOf(Instant.ofEpochMilli(cp.flushWindowStartMs), Instant.now().minusSeconds(config.stepFlushIntervalSec.toLong()))
                // Yesterday's count under yesterday's date, so the first flush of a new day rolls it.
                // A checkpoint from before the day was recorded cannot be trusted at all (it is how
                // 63,547 "today" steps survived into the next day): ask Health Connect instead.
                ledger = if (cp.ledgerDay != null) DailyLedger(cp.ledgerDay, cp.stepsWrittenToday)
                else DailyLedger(java.time.LocalDate.now(), stepsHeldToday())
                rollLedgerIfNewDay()
                lastArrivalAlertMs = 0L
                doneThisLap.clear()
                doneThisLap += cp.doneIds
                // 前往後停在這裡 survives the kill (overnight trips are exactly when HyperOS kills things).
                stayAtId = cp.stayAtId?.takeIf { id -> waypoints.any { it.id == id } }
                // Killed while dwelling: that flower counts as visited, no second 抵達 alert.
                if (cp.phase == PatrolPhase.DWELLING) waypoints.getOrNull(cp.targetWaypointIndex)?.let { doneThisLap += it.id }

                val goHome = thenReturnHome || cp.phase == PatrolPhase.RETURNING_HOME || cp.phase == PatrolPhase.PARKED || waypoints.isEmpty()
                if (thenReturnHome && cp.phase != PatrolPhase.RETURNING_HOME && cp.phase != PatrolPhase.PARKED) {
                    returnTarget = null; parkAtHome = false     // the dialog's 走回家 means the real home
                }
                val dest = returnTarget ?: cp.home
                if (goHome) {
                    loadPlan(PatrolPlanner.planReturnHome(resumeAt, dest, routeTravelMode.takeIf { it != TravelMode.WALK }))
                    settleTicks = 0
                } else {
                    // Continue with the waypoints not yet visited this lap, from where we are. Old
                    // checkpoints carry no ids: fall back to "everything before the target is done".
                    val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
                    if (cp.doneIds.isEmpty() && cp.phase != PatrolPhase.MANUAL) {
                        val pos = order.indexOf(cp.targetWaypointIndex.coerceIn(0, waypoints.size - 1))
                        if (pos > 0) order.take(pos).forEach { i -> waypoints.getOrNull(i)?.let { doneThisLap += it.id } }
                    }
                    val remaining = order.filter { i -> waypoints.getOrNull(i)?.let { it.id !in doneThisLap } ?: false }
                    loadPlan(PatrolPlanner.planLap(resumeAt, waypoints, config, remaining.ifEmpty { order }, lap))
                }

                _state.update {
                    it.copy(
                        phase = if (goHome) PatrolPhase.RETURNING_HOME else phaseFor(plan.segments.firstOrNull()?.kind ?: SegmentKind.TRAVEL),
                        travelOverride = cp.travelOverride,
                        home = cp.home, position = resumeAt, startedAtMs = cp.startedAtMs,
                        distanceWalkedM = cp.distanceWalkedM, sessionSteps = floor(stepsAccrued).toLong(),
                        stepsWrittenToday = stepsWrittenToday, lapsCompleted = cp.lapsCompleted,
                        mockAppSelected = true, healthConnectReady = steps.isAvailable, lastError = null,
                        currentWaypointIndex = if (goHome) -1 else (plan.segments.firstOrNull()?.waypointIndex ?: cp.targetWaypointIndex),
                        currentWaypointName = if (goHome) getString(R.string.svc_target_home) else (plan.segments.firstOrNull()?.waypointIndex ?: cp.targetWaypointIndex).let { i -> waypoints.getOrNull(i)?.name },
                        distanceToTargetM = if (goHome) GeoMath.distanceM(resumeAt, dest) else 0.0,
                        homeIsCustom = parkAtHome,
                    )
                }
                // A patrol checkpointed while PAUSED comes back PAUSED at this very position (nothing walks until 繼續, which plans
                // on from here as the state above says). Entered before startTicking(): this block runs on the single engine thread
                // and has no suspension point from here on, so the tick loop (a coroutine on the same thread, created only below)
                // cannot start before the pause is in - its first tick finds PAUSED and only re-pushes sim.current() at speed 0.
                // 走回家 and an emptied route (goHome) are explicit or forced walks and stay as they were.
                val paused = !goHome && ResumeCopy.restoresPaused(cp.phase, thenReturnHome)
                if (paused) setPaused(true)
                Log.i(TAG, "resumed from checkpoint (${cp.ageMs / 1000}s old) at $resumeAt, goHome=$goHome, paused=$paused")
                health("RESUME from a ${cp.ageMs / 1000}s old checkpoint (${cp.phase}, now ${state.value.phase}, ${MockHealthLog.zoneNote()})")
                _events.tryEmit(PatrolEvent.Resumed(cp.ageMs))
                lastNotificationMs = 0
                startTicking()
            } catch (e: MockNotAllowedException) {
                failNow(e.message ?: getString(R.string.svc_err_not_mock_app))
            } catch (t: Throwable) {
                Log.e(TAG, "resume failed", t)
                failNow(t.message ?: "resume failed")
            }
        }
    }

    /** Snapshot of everything a resume needs. Engine thread only. */
    private fun writeCheckpoint() {
        val s = state.value
        val h = home ?: return
        val p = s.position ?: return
        // 真實位置 keeps the last snapshot from before the switch: resuming one after a kill is the user's choice.
        if (s.phase == PatrolPhase.IDLE || s.phase == PatrolPhase.STOPPING || s.phase == PatrolPhase.STARTING || s.phase == PatrolPhase.SUSPENDED) return
        PatrolCheckpoint.save(
            this,
            PatrolCheckpoint(
                savedAtMs = System.currentTimeMillis(),
                startedAtMs = s.startedAtMs,
                home = h,
                position = p,
                routeId = store.activeRouteId.value,
                lap = lap,
                targetWaypointIndex = s.currentWaypointIndex.coerceAtLeast(0),
                phase = s.phase,
                returnTarget = if (parkAtHome) returnTarget else null,
                travelOverride = _travelOverride.value,
                ledgerDay = ledger.day,
                doneIds = doneThisLap.toList(),
                stayAtId = stayAtId,
                distanceWalkedM = s.distanceWalkedM,
                stepsAccrued = stepsAccrued,
                stepsFlushed = stepsFlushed,
                flushWindowStartMs = flushWindowStart.toEpochMilli(),
                distanceSinceFlush = distanceSinceFlush,
                stepsWrittenToday = stepsWrittenToday,
                lapsCompleted = s.lapsCompleted,
            ),
        )
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, PatrolNotifications.ID_ONGOING, notifications.ongoing(state.value), type)
        lastNotificationKey = null   // that replaced the ongoing notification behind the gate's back: what it says is unknown now
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground failed", t)
        // A location-type foreground service may only be started while the app is "eligible",
        // i.e. with a visible activity. Anything else (an adb broadcast, a stale notification
        // action) lands here, and the raw platform message is useless to a user.
        val friendly = if (t is SecurityException || t.javaClass.simpleName.contains("ForegroundServiceStartNotAllowed")) {
            getString(R.string.svc_err_background_start)
        } else {
            t.message ?: "startForeground failed"
        }
        failNow(friendly)
        false
    }

    /** The active route's travel mode; WALK for an ordinary patrol. */
    private val routeTravelMode: TravelMode
        get() = store.activeRoute()?.travelMode ?: TravelMode.WALK

    private fun loadLap(from: LatLng, startIndex: Int = 0) {
        var order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        doneThisLap.clear()
        replanPending = false
        if (lap == 0 && startIndex > 0) {
            // 從這裡開始: the ones before it are not visited this lap, and a re-plan must not add them back.
            order.take(order.indexOf(startIndex).coerceAtLeast(0)).forEach { i -> waypoints.getOrNull(i)?.let { doneThisLap += it.id } }
            order = order.drop(startIndex)
        }

        val mode = routeTravelMode
        val first = order.firstOrNull()?.let { waypoints.getOrNull(it) }
        val next = if (lap == 0 && mode != TravelMode.WALK && first != null) {
            // A trip: cover the (possibly long) leg to the first place at vehicle speed - no steps,
            // no planting - then wander it on foot for its dwell time regardless of the global
            // orbit setting, because that walk is the whole point of going there. Any further
            // waypoints are walked as usual from where the wander ends.
            val trip = PatrolPlanner.planTripTo(
                from, first.latLng, config, mode,
                // The only reader of Waypoint.radiusM / dwellSec left: a decor trip's wander (set in DecorHunt).
                wanderRadiusM = first.radiusM,
                wanderSec = first.dwellSec.coerceAtLeast(60),
            )
            val rest = order.drop(1)
            if (rest.isEmpty()) trip else {
                val tail = PatrolPlanner.planLap(trip.end ?: first.latLng, waypoints, config, rest, lap)
                PatrolPlan.of(trip.segments + tail.segments)
            }
        } else {
            PatrolPlanner.planLap(from, waypoints, config, order, lap)
        }
        loadPlan(next)
        Log.i(TAG, "lap $lap loaded: ${plan.segments.size} segments, ${"%.0f".format(plan.totalLengthM)} m, mode=$mode")
    }

    // ------------------------------------------------------------------ tick loop (engine thread)

    private fun startTicking() {
        tickJob?.cancel()
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        tickJob = lifecycleScope.launch(engine) {
            while (isActive && pendingFinish == null) {
                try {
                    tick()
                } catch (t: Throwable) {
                    Log.e(TAG, "tick failed", t)
                    _state.update { it.copy(lastError = t.message) }
                }
                if (pendingFinish != null) break
                delay(config.tickMs)
            }
            val finish = pendingFinish ?: return@launch
            withContext(NonCancellable) { runFinish(finish) }
        }
    }

    private suspend fun tick() {
        val nowMs = SystemClock.elapsedRealtime()
        val gapMs = nowMs - lastTickElapsedMs
        // Without the wakelock the CPU may have slept since the last tick: that tick walks nothing, so a 繼續 handled
        // before it does not get a clamped 3 s step. Moving phases always hold the lock (taken before the first tick).
        val dt = StationaryPower.tickSeconds(nowMs, lastTickElapsedMs, wakeLockHeld = wakeLock?.isHeld == true)
        lastTickElapsedMs = nowMs
        // Read once, so the pushes below and the wakelock at the end of this tick agree on whether the screen is on.
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true

        when (state.value.phase) {
            // Still or not, screen on or off: a fix every second while the mock is on (StationaryPower, PLAN O5).
            PatrolPhase.PAUSED -> mock.push(sim.current())
            PatrolPhase.PARKED -> {
                // Keep the game fed with fixes at the parked spot (the saved home, never the real
                // position); a silent provider reads as "GPS lost".
                val h = returnTarget ?: run { pendingFinish = Finish.Stop; return }
                mock.pushRaw(h, accuracyM = 5f)
            }
            PatrolPhase.MANUAL -> {
                val (bearing, magnitude) = _joystick.value.drive()
                val s = sim.advanceManual(dt, bearing, magnitude)
                mock.push(s)
                account(s)
                _state.update { it.copy(position = s.position, speedMps = s.speedMps, distanceToTargetM = 0.0) }
            }
            PatrolPhase.WALKING, PatrolPhase.DWELLING -> {
                val s = sim.advance(dt)
                mock.push(s)
                account(s)
                val stayHere = s.arrivedAtWaypoint != null && onArrived(s.arrivedAtWaypoint)
                _state.update {
                    it.copy(
                        phase = phaseFor(s.kind), position = s.position, speedMps = s.speedMps,
                        currentWaypointIndex = s.waypointIndex ?: it.currentWaypointIndex,
                        currentWaypointName = s.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name } ?: it.currentWaypointName,
                        distanceToTargetM = distanceToTarget(s),
                    )
                }
                // 前往後停在這裡: freeze right here. Whatever comes next (the next lap, or a lap end's walk home) waits for 繼續.
                if (stayHere) setPaused(true)
                if (s.lapFinished) {
                    onLapFinished(s, holdReturn = stayHere)
                } else if (replanPending && s.kind == SegmentKind.TRAVEL && s.countsSteps) {
                    // The orbit / vehicle leg that was in progress when the edit came in is over.
                    replanPending = false
                    replanRemaining(reason = "deferred edit")
                }
            }
            PatrolPhase.RETURNING_HOME -> {
                val h = returnTarget ?: home ?: run { pendingFinish = Finish.Stop; return }
                if (returnTeleport || sim.finished) {
                    // A teleport 回家 lands on this first tick: seat the simulator there too (what park() does),
                    // or a walk 回家 / 立刻前往 / the joystick arriving while it settles would plan from the spot
                    // we just left and drag the avatar back to it.
                    if (returnTeleport && settleTicks == 0) loadPlan(PatrolPlanner.planReturnHome(h, h))
                    mock.pushRaw(h, accuracyM = 5f)
                    _state.update { it.copy(position = h, speedMps = 0.0, distanceToTargetM = 0.0) }
                    settleTicks++
                    if (settleTicks >= SETTLE_TICKS) {
                        if (parkAtHome) park(h) else pendingFinish = Finish.ReturnedHome
                    }
                } else {
                    val s = sim.advance(dt)
                    mock.push(s)
                    account(s)
                    _state.update {
                        it.copy(position = s.position, speedMps = s.speedMps, distanceToTargetM = GeoMath.distanceM(s.position, h))
                    }
                }
            }
            // 真實位置: no mock to feed and nothing walks; the notification and the day roll below still run.
            PatrolPhase.SUSPENDED -> Unit
            else -> Unit
        }

        if (pendingFinish == null) maybeFlushSteps(force = false)
        maybeUpdateNotification()
        // Not once a finish is pending (ReturnedHome set above, or a 停止 that landed during the flush): 已切到真實位置 would flash right before it.
        if (pendingFinish == null) maybeAskRealForMaps(nowMs)
        // PAUSED / PARKED never flush, so the day would not roll there: this is what rolls it (at the first tick after the
        // CPU wakes, if it slept through midnight).
        rollLedgerIfNewDay()
        // Watch the mock (MockGuard): permission, failed pushes, ticks that did not run, the screen going off.
        if (pendingFinish == null) guardMock(nowMs, gapMs, interactive)
        // The lock is dropped only on 真實位置 (StationaryPower, 2026-10-08); anywhere else a lapsed 12 h timeout is taken
        // again right here (read the phase now: a switch can land while the flush above suspends).
        if (StationaryPower.plan(state.value.phase, interactive).wakeLock) {
            if (wakeLock?.isHeld != true) acquireWakeLock()
        } else if (wakeLock != null) {
            // About to let the CPU sleep, maybe for hours: write the steps still pending now (one flush interval's worth at
            // most), or the next flush would spread them over the whole sleep. Not with a finish pending: runFinish writes them.
            if (pendingFinish == null) maybeFlushSteps(force = true)
            // The flush suspends: decide again on the phase as it is now (a 繼續 may have landed meanwhile).
            if (!StationaryPower.plan(state.value.phase, interactive).wakeLock) releaseWakeLock()   // leaves it null: a sleeping tick does not come back here
        }

        // Cheap insurance against a thermal kill: a few hundred bytes every few seconds.
        if (nowMs - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS) {
            lastCheckpointMs = nowMs
            writeCheckpoint()
        }
    }

    /**
     * A saved home reached. It is not where the phone is, so releasing the mock would drop the
     * game onto the real GPS, which may be another country. Stay put with the mock on; 繼續 starts
     * the next lap from here, another 回家 can move on (to the real position or another home), and
     * 停止 is the deliberate way out.
     */
    private fun park(h: LatLng) {
        loadPlan(PatrolPlanner.planReturnHome(h, h))
        _state.update {
            it.copy(phase = PatrolPhase.PARKED, position = h, speedMps = 0.0, distanceToTargetM = 0.0,
                currentWaypointIndex = -1, currentWaypointName = getString(R.string.svc_target_home))
        }
        lastNotificationMs = 0
        notifications.parkedAtHome(vibrate = config.vibrateOnArrival)
        _events.tryEmit(PatrolEvent.ParkedAtHome)
        Log.i(TAG, "parked at the saved home $h; mock stays on")
        // A pad that was opened during the walk home takes over now instead of sitting there lit and dead.
        if (_joystick.value.enabled) enterManual()
    }

    private suspend fun runFinish(finish: Finish) {
        val before = state.value
        health("FINISH ${finish.javaClass.simpleName} from ${before.phase}")
        _state.update { it.copy(phase = PatrolPhase.STOPPING) }
        runCatching { maybeFlushSteps(force = true) }.onFailure { Log.w(TAG, "final flush failed", it) }
        mock.stop()
        // The game now sees the real GPS. Unless it already did (真實位置) or the walk home ended there, that was a jump.
        if (finish != Finish.ReturnedHome && before.phase != PatrolPhase.SUSPENDED) {
            val at = before.position
            val h = home
            if (at != null && h != null) recordJump(at, h, "stop")
        }
        when (finish) {
            Finish.ReturnedHome -> {
                notifications.returnedHome(vibrate = config.vibrateOnArrival)
                _events.tryEmit(PatrolEvent.ReturnedHome)
            }
            Finish.Stop -> _events.tryEmit(PatrolEvent.Stopped)
            is Finish.Failed -> { _events.tryEmit(PatrolEvent.Error(finish.message)); notifications.error(finish.message) }
        }
        withContext(Dispatchers.Main) { teardown((finish as? Finish.Failed)?.message) }
    }

    private fun account(s: Sample) {
        if (s.distanceDeltaM <= 0.0) return
        // Vehicle legs move the position but produce no steps; nobody walks while driving. Only
        // the metres actually walked in this tick count (a tick can end a drive and start a walk).
        if (s.walkedDeltaM > 0.0) {
            stepsAccrued += s.walkedDeltaM / config.strideM
            distanceSinceFlush += s.walkedDeltaM
        }
        _state.update {
            it.copy(distanceWalkedM = it.distanceWalkedM + s.distanceDeltaM, sessionSteps = floor(stepsAccrued).toLong())
        }
    }

    private fun distanceToTarget(s: Sample): Double {
        val idx = s.waypointIndex ?: return 0.0
        val wp = waypoints.getOrNull(idx) ?: return 0.0
        return GeoMath.distanceM(s.position, wp.latLng)
    }

    private fun phaseFor(kind: SegmentKind) = if (kind == SegmentKind.ORBIT) PatrolPhase.DWELLING else PatrolPhase.WALKING

    /** Returns true when this is the flower 前往後停在這裡 was aimed at: the caller pauses the patrol here. */
    private fun onArrived(index: Int): Boolean {
        val wp = waypoints.getOrNull(index)
        val name = wp?.name ?: "#${index + 1}"
        wp?.let { doneThisLap += it.id }
        val stay = wp != null && wp.id == stayAtId
        if (stay) stayAtId = null
        Log.i(TAG, "arrived at $index ($name)")
        _events.tryEmit(PatrolEvent.ArrivedAtWaypoint(index, name))
        // 自動拉花 pauses and then RESUMES the patrol: at a 前往後停在這裡 flower that would walk off again. The user
        // collects there by hand (debug builds only have 自動拉花 anyway, PLAN O4).
        if (!stay) NectarRunner.onArrived(this, name)
        // A vehicle is for getting somewhere: it drops the player off at the first flower and the
        // walking (planting, steps) starts there. Otherwise a forgotten 汽車 would zoom round a
        // whole lap planting nothing.
        // Except the arrival a 瞬移 lands on (TeleportRules): the jump keeps whatever vehicle is in force.
        val vehicle = _travelOverride.value
        val jumpedHere = teleportTargetId
        teleportTargetId = null
        if (TeleportRules.dropsVehicleOnArrival(vehicle, arrivedId = wp?.id, teleportTargetId = jumpedHere)) {
            Log.i(TAG, "arrived by $vehicle → back to walking")
            autoDropPending = true
            _travelOverride.value = null   // the collector applies it (onTravelOverrideChanged)
        } else if (vehicle != null && !vehicle.countsSteps) {
            Log.i(TAG, "arrived after a 瞬移 → staying on $vehicle")
        }
        if (stay) {
            // Always said, whatever 抵達提醒 is set to: this is the one arrival the user asked to be stopped at (overnight).
            Log.i(TAG, "arrived at $name: staying here (前往後停在這裡)")
            health("STAY at the flower asked for: paused there")
            notifications.stayedAt(name, vibrate = config.vibrateOnArrival)
            _events.tryEmit(PatrolEvent.StayedAt(name))
            return true
        }
        if (!config.notifyOnArrival) return false
        // Rate limit: passing several flowers in a row must not turn into a burst of buzzes.
        val now = SystemClock.elapsedRealtime()
        val gapMs = config.arrivalAlertMinGapSec * 1000L
        val quiet = lastArrivalAlertMs != 0L && now - lastArrivalAlertMs < gapMs
        lastArrivalAlertMs = now
        notifications.arrived(name, vibrate = config.vibrateOnArrival && !quiet)
        return false
    }

    /**
     * [holdReturn]: the lap ended at the flower 前往後停在這裡 stopped at, so a lap end that would walk home (lap limit,
     * ONCE, nothing left) waits: the patrol stays paused there, and 繼續 starts that walk home ([returnOnResume]).
     */
    private fun onLapFinished(s: Sample, holdReturn: Boolean = false) {
        teleportTargetId = null
        val finishedLap = lap
        val completed = finishedLap + 1
        _events.tryEmit(PatrolEvent.LapFinished(finishedLap))
        _state.update { it.copy(lapsCompleted = completed) }
        lap++

        val limit = config.autoReturnAfterLaps
        if (limit > 0 && completed >= limit) {
            Log.i(TAG, "completed $completed lap(s), limit $limit → returning home")
            if (holdReturn) returnOnResume = true else beginReturnHome()
            return
        }
        val nextOrder = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        if (nextOrder.isEmpty()) {
            Log.i(TAG, "route finished (ONCE) → returning home")
            if (holdReturn) returnOnResume = true else beginReturnHome()
            return
        }
        loadLap(s.position)
        // A lap with nowhere to walk (one flower and no orbiting, or flowers on top of each other)
        // would "arrive" again on the very next tick and spin the notification once a second.
        if (plan.totalLengthM < MIN_LAP_M) {
            Log.i(TAG, "lap $lap is degenerate (${"%.1f".format(plan.totalLengthM)} m) → nothing left to walk, returning home")
            if (holdReturn) returnOnResume = true else beginReturnHome()
        }
    }

    // ------------------------------------------------------------------ live changes (engine thread)

    /** A settings screen edit while walking: re-read everything and hand the simulator the new speed. */
    private suspend fun onConfigChanged(key: String?) {
        if (state.value.phase == PatrolPhase.IDLE) return
        val fresh = prefs.config()
        if (fresh == config) return
        val speedChanged = fresh.speedMps != config.speedMps || fresh.vehicleSpeedsKmh != config.vehicleSpeedsKmh
        val stepsSwitchedOn = fresh.injectSteps && !config.injectSteps
        config = fresh
        if (stepsSwitchedOn) reseedLedger("寫入步數 on")
        sim.updateConfig(fresh)
        // The speed that is actually in force right now: the vehicle override's, else walking.
        val effectiveKmh = _travelOverride.value?.let { fresh.speedKmhOf(it) } ?: (fresh.speedMps * 3.6)
        Log.i(TAG, "config changed ($key): walk ${"%.1f".format(fresh.speedMps * 3.6)} km/h, vehicles ${Prefs.VEHICLE_SPEED_KEYS.keys.joinToString { "${it.name}=${fresh.speedTextOf(it)}" }}, in force ${"%.1f".format(effectiveKmh)} km/h, orbit=${fresh.orbitAtWaypoints}")
        if (speedChanged) _events.tryEmit(PatrolEvent.ConfigChanged(effectiveKmh))
        lastNotificationMs = 0
    }

    /**
     * The waypoint list (of the active route) changed while walking: keep the leg in progress if
     * it still makes sense, then continue with whatever is left of the lap, in the new order.
     */
    private fun onWaypointsChanged(list: List<Waypoint>) {
        val previous = waypoints
        waypoints = list
        val p = state.value.phase
        if (p != PatrolPhase.WALKING && p != PatrolPhase.DWELLING && p != PatrolPhase.PAUSED) return
        if (list == previous) return
        if (list.isEmpty()) {
            Log.i(TAG, "every waypoint removed while walking → returning home")
            beginReturnHome()
            return
        }
        val cur = sim.current()
        val curWp = cur.waypointIndex?.let { previous.getOrNull(it) }
        val midLeg = cur.kind == SegmentKind.ORBIT || !cur.countsSteps
        if (midLeg && curWp != null && curWp in list) {
            // Finish the orbit (or the drive) we are in the middle of, then take the new list up.
            replanPending = true
            Log.i(TAG, "waypoints edited (${previous.size} → ${list.size}); applying after the current leg")
            return
        }
        replanRemaining(reason = "waypoints edited (${previous.size} → ${list.size})")
    }

    /**
     * Re-plans the rest of the current lap from where we are: everything not yet visited this lap,
     * in route order, with [firstIndex] (立刻前往) moved to the front when given. The leg in
     * progress is kept when it belongs to a waypoint that still exists unchanged.
     */
    private fun replanRemaining(reason: String, firstIndex: Int? = null) {
        val cur = sim.current()
        val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        var rest = order.filter { i -> waypoints.getOrNull(i)?.let { it.id !in doneThisLap } ?: false }
        if (firstIndex != null && firstIndex in waypoints.indices) {
            rest = listOf(firstIndex) + rest.filter { it != firstIndex }
        }

        // Keep the leg we are on if it is still for a waypoint that exists unchanged (its index may
        // have moved). Never for a 立刻前往 to somewhere else: that is an explicit change of target.
        val keep = ArrayList<RouteSegment>()
        var kept: Int? = null
        val oldIdx = cur.waypointIndex
        if (oldIdx != null) {
            val legs = sim.remainingLegsOfCurrentWaypoint()
            // The plan's index refers to the list it was made from; the same flower may now sit elsewhere.
            val wp = planWaypoints.getOrNull(oldIdx)
            val newIdx = if (wp != null) waypoints.indexOf(wp) else -1
            if (legs.isNotEmpty() && newIdx >= 0 && (firstIndex == null || firstIndex == newIdx)) {
                keep += legs.map { it.copy(waypointIndex = newIdx) }
                kept = newIdx
            }
        }
        if (kept != null) rest = rest.filter { it != kept }

        val start = keep.lastOrNull()?.to ?: cur.position
        val tail = if (rest.isEmpty()) PatrolPlan.EMPTY else PatrolPlanner.planLap(start, waypoints, config, rest, lap)
        val merged = PatrolPlan.of(keep + tail.segments)
        if (merged.isEmpty) {
            // Nothing left in this lap at all: treat it as finished, which loads the next one (or goes home).
            Log.i(TAG, "re-plan ($reason): nothing left → lap finished")
            onLapFinished(cur)
            return
        }
        replanPending = false
        loadPlan(merged)
        _state.update {
            val first = plan.segments.first()
            it.copy(
                phase = if (it.phase == PatrolPhase.PAUSED) it.phase else phaseFor(first.kind),
                currentWaypointIndex = first.waypointIndex ?: -1,
                currentWaypointName = first.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name },
                distanceToTargetM = first.waypointIndex?.let { i -> waypoints.getOrNull(i)?.let { w -> GeoMath.distanceM(cur.position, w.latLng) } } ?: 0.0,
            )
        }
        lastNotificationMs = 0
        _events.tryEmit(PatrolEvent.Replanned(reason))
        Log.i(TAG, "re-planned ($reason): kept ${keep.size} leg(s), then ${rest.size} waypoint(s), ${"%.0f".format(plan.totalLengthM)} m")
    }

    /** The waypoint list the current [plan] was built from: its segment indices refer to this one. */
    private var planWaypoints: List<Waypoint> = emptyList()

    /** Every plan goes through here so [planWaypoints] always matches [plan]. */
    private fun loadPlan(p: PatrolPlan) {
        plan = p
        planWaypoints = waypoints
        sim.load(p)
    }

    /** 立刻前往: make [index] the next target, keeping the rest of the lap after it. */
    /** 立刻前往 / 瞬移過去. [teleport] jumps to the flower's circle edge first (manual choice, PLAN K). */
    /** [stay] = 前往後停在這裡: travel there as 立刻前往 does (on foot or by the vehicle in force) and pause on arrival. */
    private fun goToWaypoint(index: Int, teleport: Boolean = false, stay: Boolean = false) {
        val p = state.value.phase
        if (index !in waypoints.indices) return
        if (p == PatrolPhase.SUSPENDED) { refuseWhileReal(); return }
        if (p == PatrolPhase.STARTING) { pendingGoTo = GoTo(index, teleport, stay); return }
        if (p == PatrolPhase.IDLE || p == PatrolPhase.STOPPING) return
        // A new target: an earlier 瞬移's "keep the vehicle on arrival" was for the flower it jumped to, and an earlier
        // 前往後停在這裡 for its own flower.
        teleportTargetId = null
        returnOnResume = false
        stayAtId = if (stay && !teleport) waypoints[index].id else null
        // 立刻前往 takes the walk back from the pad in every phase: a PAUSED pad would otherwise come back
        // on 繼續 with its old cruise (enterManual) and drop this target.
        if (_joystick.value.enabled) _joystick.value = JoystickInput()
        if (!teleport && (p == PatrolPhase.WALKING || p == PatrolPhase.DWELLING || p == PatrolPhase.PAUSED)) {
            doneThisLap.remove(waypoints[index].id)
            replanRemaining(reason = "go to ${waypoints[index].name}", firstIndex = index)
            // 前往後停在這裡 is "go now and stop there": a paused patrol sets off (立刻前往 alone stays paused, as before).
            if (stay && state.value.phase == PatrolPhase.PAUSED) setPaused(false)
            return
        }
        // Leave the parking spot / the joystick / a return in progress (or jump) and walk the lap
        // starting at that flower.
        if (p == PatrolPhase.RETURNING_HOME) settleTicks = 0
        val here = sim.current().position
        val from = if (teleport) PatrolPlanner.teleportEntry(waypoints[index], here) else here
        doneThisLap.clear()
        val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        val rest = listOf(index) + order.filter { it != index }
        loadPlan(PatrolPlanner.planLap(from, waypoints, config, rest, lap))
        if (teleport) {
            mock.pushRaw(from, accuracyM = 5f)
            recordJump(here, from, "teleport to flower")
            teleportTargetId = waypoints[index].id
            Log.i(TAG, "teleported to ${waypoints[index].name} at $from (manual, was $p, vehicle ${_travelOverride.value ?: "walk"})")
        }
        // A 瞬移 keeps the patrol as it was (paused stays paused, TeleportRules); a walking 立刻前往 sets off.
        val next = if (teleport) TeleportRules.phaseAfter(p, plan.segments.first().kind) else phaseFor(plan.segments.first().kind)
        _state.update {
            it.copy(phase = next, position = from, currentWaypointIndex = index,
                currentWaypointName = waypoints[index].name,
                speedMps = if (next == PatrolPhase.PAUSED) 0.0 else it.speedMps)
        }
        lastNotificationMs = 0
        _events.tryEmit(PatrolEvent.Replanned((if (teleport) "teleport to " else "go to ") + waypoints[index].name))
    }

    /** Set right before dropping the override on arrival, so the change is reported as automatic. */
    private var autoDropPending = false

    /**
     * The flower a 瞬移 landed next to: arriving at it keeps the vehicle (TeleportRules). An id, not an index, so a
     * waypoint edit before the arrival does not lose it; cleared by any other arrival, a skip, a new lap, 立刻前往,
     * 回家 and the joystick. Engine thread only.
     */
    private var teleportTargetId: String? = null

    /**
     * 前往後停在這裡 (2026-10-08, "我半夜可能想讓它走很遠很遠，然後我早上起來再划獎勵"): the waypoint id the patrol pauses
     * at on arrival. Kept in the checkpoint; cleared by arriving there, another 立刻前往, a skip, 回家 and the joystick.
     * Engine thread (teardown on main resets it like the other fields).
     */
    private var stayAtId: String? = null
        set(v) {
            field = v
            val name = v?.let { id -> waypoints.firstOrNull { it.id == id }?.name }
            _state.update { it.copy(stayAtName = name) }
        }

    /** A lap end that would have walked home happened at the stay flower: 繼續 starts that walk (onLapFinished). */
    private var returnOnResume = false

    private fun onTravelOverrideChanged(mode: TravelMode?) {
        val automatic = autoDropPending && mode == null
        autoDropPending = false
        if (state.value.phase == PatrolPhase.IDLE) {
            _state.update { it.copy(travelOverride = mode) }
            return
        }
        applyTravelOverride(mode, automatic)
    }

    private fun applyTravelOverride(mode: TravelMode?, automatic: Boolean) {
        sim.travelOverride = mode
        _state.update { it.copy(travelOverride = mode) }
        lastNotificationMs = 0
        _events.tryEmit(PatrolEvent.TravelModeChanged(mode, automatic))
        Log.i(TAG, "travel override → ${mode ?: "walk"} (${if (automatic) "auto" else "user"})")
    }

    /** Joystick shown / hidden. Taking over is immediate; giving back re-plans from wherever we ended up. */
    private fun onJoystickToggled(enabled: Boolean) {
        val p = state.value.phase
        if (enabled) {
            // RETURNING_HOME too: the pad interrupts the walk home (reported 2026-09-14: "wanted to
            // steer somewhere on the way back, it just kept going home"). Putting the pad away
            // picks the lap up from wherever we are; 回家 again re-aims.
            if (p == PatrolPhase.WALKING || p == PatrolPhase.DWELLING || p == PatrolPhase.PARKED || p == PatrolPhase.RETURNING_HOME) enterManual()
            // PAUSED keeps its frozen position; 繼續 goes to MANUAL because the joystick is up.
        } else if (p == PatrolPhase.MANUAL) {
            leaveManual()
        }
    }

    private fun enterManual() {
        teleportTargetId = null   // the pad decides where we go now
        stayAtId = null
        returnOnResume = false
        _state.update {
            it.copy(phase = PatrolPhase.MANUAL, currentWaypointIndex = -1,
                currentWaypointName = getString(R.string.svc_target_joystick), distanceToTargetM = 0.0)
        }
        lastNotificationMs = 0
        Log.i(TAG, "joystick took over at ${sim.current().position}")
    }

    private fun leaveManual() {
        if (waypoints.isEmpty()) { beginReturnHome(); return }
        // Whatever was visited stays visited; the rest of the lap continues from here.
        val cur = sim.current()
        val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        val rest = order.filter { i -> waypoints.getOrNull(i)?.let { it.id !in doneThisLap } ?: false }
        if (rest.isEmpty()) {
            _state.update { it.copy(phase = PatrolPhase.WALKING) }
            onLapFinished(cur)
            return
        }
        loadPlan(PatrolPlanner.planLap(cur.position, waypoints, config, rest, lap))
        val first = plan.segments.first()
        _state.update {
            it.copy(phase = phaseFor(first.kind), currentWaypointIndex = first.waypointIndex ?: -1,
                currentWaypointName = first.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name })
        }
        lastNotificationMs = 0
        _events.tryEmit(PatrolEvent.Replanned("joystick off"))
        Log.i(TAG, "joystick put away at ${cur.position}; ${rest.size} waypoint(s) left in lap $lap")
    }

    // ------------------------------------------------------------------ steps (engine thread)

    private suspend fun maybeFlushSteps(force: Boolean) {
        val now = Instant.now()
        val windowSec = Duration.between(flushWindowStart, now).seconds
        if (!force && windowSec < config.stepFlushIntervalSec) return
        val pending = floor(stepsAccrued).toLong() - stepsFlushed
        if (pending <= 0) { flushWindowStart = now; distanceSinceFlush = 0.0; return }

        if (!config.injectSteps || !steps.isAvailable) {
            stepsFlushed += pending; flushWindowStart = now; distanceSinceFlush = 0.0; return
        }
        rollLedgerIfNewDay()
        val room = ledger.room(config.dailyStepCap)
        val toWrite = minOf(pending, room)
        if (toWrite < pending) {
            Log.i(TAG, "writing $toWrite of $pending steps (daily room $room)")
        }
        val distance = distanceSinceFlush * (toWrite.toDouble() / pending)
        val ok = if (toWrite > 0) steps.write(flushWindowStart, now, toWrite, distance) else true
        if (ok) {
            ledger.add(toWrite)
            stepsFlushed += pending           // steps beyond the daily cap are dropped on purpose
            flushWindowStart = now
            distanceSinceFlush = 0.0
            _state.update { it.copy(stepsWrittenToday = stepsWrittenToday) }
            if (toWrite > 0) _events.tryEmit(PatrolEvent.StepsWritten(toWrite, stepsWrittenToday))
        } else if (windowSec > 45 * 60) {
            Log.w(TAG, "dropping $pending steps: Health Connect unavailable for ${windowSec}s")
            stepsFlushed += pending; flushWindowStart = now; distanceSinceFlush = 0.0
        }
    }

    // ------------------------------------------------------------------ control (engine thread)

    private fun setPaused(paused: Boolean) {
        val p = state.value.phase
        if (paused && (p == PatrolPhase.WALKING || p == PatrolPhase.DWELLING || p == PatrolPhase.MANUAL)) {
            _state.update { it.copy(phase = PatrolPhase.PAUSED, speedMps = 0.0) }
        } else if (!paused && p == PatrolPhase.PAUSED) {
            when {
                _joystick.value.enabled -> enterManual()
                // Stayed at the 前往後停在這裡 flower right as the lap ended on a walk home: 繼續 is that walk.
                returnOnResume -> { returnOnResume = false; beginReturnHome() }
                // Paused while the joystick had us off-route, and the joystick was put away since:
                // the old plan says nothing about where we are now, so pick the route up from here.
                sim.inManual -> leaveManual()
                else -> _state.update { it.copy(phase = phaseFor(sim.current().kind)) }
            }
        } else if (!paused && p == PatrolPhase.PARKED) {
            // 繼續 from the saved home: another lap, from here.
            if (waypoints.isEmpty()) {
                _events.tryEmit(PatrolEvent.Error(getString(R.string.svc_err_no_waypoints)))
                return
            }
            if (_joystick.value.enabled) { enterManual(); return }
            // ONCE has nothing left after lap 0; 再巡一圈 means a fresh lap, not an empty one.
            if (PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode).isEmpty()) lap = 0
            loadLap(sim.current().position)
            _state.update {
                it.copy(
                    phase = if (plan.isEmpty) PatrolPhase.WALKING else phaseFor(plan.segments[0].kind),
                    currentWaypointIndex = plan.segments.firstOrNull()?.waypointIndex ?: -1,
                    currentWaypointName = plan.segments.firstOrNull()?.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name },
                )
            }
            Log.i(TAG, "left the parking spot for lap $lap")
        } else if (p == PatrolPhase.SUSPENDED) {
            // Nothing moves on the real position; NectarRunner's 繼續 after its run is applied on the way back.
            resumeOnLeave = RealMode.resumeCarriesOver(phaseBeforeReal, resume = !paused)
        }
        lastNotificationMs = 0
    }

    private fun skipWaypoint() {
        val p = state.value.phase
        if (p != PatrolPhase.WALKING && p != PatrolPhase.DWELLING && p != PatrolPhase.PAUSED) return
        teleportTargetId = null   // skipping the flower a 瞬移 aimed at: the next arrival is a real one
        stayAtId = null           // ...and a skipped 前往後停在這裡 target is not where to stop any more
        val cur = sim.current()
        val idx = cur.waypointIndex ?: return
        waypoints.getOrNull(idx)?.let { doneThisLap += it.id }
        val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        val pos = order.indexOf(idx)
        val rest = if (pos >= 0) order.drop(pos + 1) else emptyList()
        if (rest.isEmpty()) { onLapFinished(cur); return }
        loadPlan(PatrolPlanner.planLap(cur.position, waypoints, config, rest, lap))
    }

    /**
     * Automatic returns (lap limit, ONCE, no waypoints left) go back to wherever the last 回家 went.
     * They and the notification's 回家 always walk: only a picker row (or the debug flag) ever teleports.
     */
    private fun beginReturnHome() = beginReturnHome(if (parkAtHome) returnTarget else null, teleport = false)

    /**
     * 回家. [dest] null = the real position the patrol started from (arriving releases the mock);
     * otherwise one of the saved homes (arriving parks, mock on). Allowed while PARKED (move on to
     * the real position or another home) and while RETURNING_HOME (change of mind: re-aim).
     * [teleport] jumps there instead of walking: only a picker row (or the debug flag `--ez teleport true`)
     * passes true, once, for that 回家.
     */
    private fun beginReturnHome(dest: LatLng?, teleport: Boolean) {
        val p = state.value.phase
        if (p == PatrolPhase.IDLE || p == PatrolPhase.STOPPING) return
        if (p == PatrolPhase.SUSPENDED) { refuseWhileReal(); return }
        // Already returning: a second 回家 with another destination simply re-aims; the same
        // destination is a no-op. (Reported 2026-09-14: "changed my mind on the way, could not.")
        // Walking vs teleporting to it counts as a different choice, so it re-aims too.
        if (p == PatrolPhase.RETURNING_HOME && dest == returnTarget && teleport == returnTeleport) return
        if (p == PatrolPhase.RETURNING_HOME && dest == null && returnTarget == home && teleport == returnTeleport) return
        if (p == PatrolPhase.STARTING) { requestStop(); return }
        val real = home ?: run { requestStop(); return }
        val h = dest ?: real
        returnTarget = h
        parkAtHome = dest != null
        returnTeleport = teleport
        teleportTargetId = null
        stayAtId = null
        returnOnResume = false
        _joystick.value = JoystickInput()
        val from = sim.current().position
        if (teleport) recordJump(from, h, "teleport home")
        settleTicks = 0
        // A trip that was driven out is driven back: walking 20 km home would take hours, and the
        // route already declares how such legs are covered. Ordinary patrols still walk.
        val mode = routeTravelMode.takeIf { it != TravelMode.WALK }
        if (!teleport) {
            loadPlan(PatrolPlanner.planReturnHome(from, h, mode))
        }
        _state.update {
            it.copy(phase = PatrolPhase.RETURNING_HOME, currentWaypointName = getString(R.string.svc_target_home),
                distanceToTargetM = GeoMath.distanceM(from, h), homeIsCustom = parkAtHome)
        }
        lastNotificationMs = 0
        Log.i(TAG, "returning ${if (parkAtHome) "to the saved home" else "to the real position"} (${if (teleport) "TELEPORT" else "WALK"}, ${mode ?: TravelMode.WALK}) from $from to $h, ${"%.0f".format(GeoMath.distanceM(from, h))} m")
    }

    private fun requestStop() {
        when (state.value.phase) {
            PatrolPhase.IDLE -> stopSelf()
            PatrolPhase.STARTING -> stopRequested = true      // start coroutine checks this at its next step
            PatrolPhase.STOPPING -> Unit
            else -> if (pendingFinish == null) pendingFinish = Finish.Stop
        }
    }

    /** Remembers a jump the game just saw (for the 上次跳躍 readout); short ones are ignored. */
    private fun recordJump(from: LatLng, to: LatLng, why: String) {
        val jump = LocationJump.of(System.currentTimeMillis(), from, to) ?: return
        prefs.lastJump = jump
        Log.i(TAG, "location jump ($why): ${"%.0f".format(jump.distanceM)} m")
    }

    // ------------------------------------------------------------------ 真實位置 (engine thread)

    /** Phase to restore on 回到虛擬位置. */
    private var phaseBeforeReal: PatrolPhase? = null

    /** A 繼續 that arrived on the real position for a patrol that left paused (RealMode.resumeCarriesOver). */
    private var resumeOnLeave = false

    /**
     * 真實位置: remove the mock so every app sees the real GPS; keep position, plan, lap, visited
     * flowers and steps frozen. Nothing is pushed and the CPU may sleep until [leaveRealMode].
     */
    private fun enterRealMode(reason: String) {
        val p = state.value.phase
        if (!RealMode.canEnter(p)) return
        phaseBeforeReal = p
        resumeOnLeave = false
        _state.update { it.copy(phase = PatrolPhase.SUSPENDED, speedMps = 0.0) }
        // A cruise never outlives the user's intent: coming back is a jump, and must not walk off on its own after it.
        _joystick.update { it.withCruise(false) }
        mock.stop()
        home?.let { h -> state.value.position?.let { at -> recordJump(at, h, "real position") } }
        // Survives a kill: the resume dialog must then say the game already sees the real GPS.
        prefs.suspendedAtMs = System.currentTimeMillis()
        releaseWakeLock()
        lastNotificationMs = 0
        _events.tryEmit(PatrolEvent.SwitchedToReal(reason))
        // Switched (from the question, the bar, the app or a leak): a Maps question still up is answered.
        lifecycleScope.launch(Dispatchers.Main) { MapsPrompt.dismiss(this@PatrolService) }
        Log.i(TAG, "real position on ($reason) from $p at ${state.value.position}")
        health("REAL POSITION on ($reason) from $p")
    }

    /** 回到虛擬位置: re-install the mock at the kept position and pick up where we were. */
    private fun leaveRealMode() {
        // A 停止 already on its way would release the mock again right after this jump: let it finish instead.
        if (state.value.phase != PatrolPhase.SUSPENDED || pendingFinish != null) return
        // The mock-location app may have been changed meanwhile: say so plainly (the platform's text is raw)
        // and stay on the real position, where 停止 still works.
        if (!mock.isMockAppSelected()) {
            _events.tryEmit(PatrolEvent.Error(getString(R.string.svc_err_not_mock_app)))
            return
        }
        try {
            mock.start(config)
        } catch (e: MockNotAllowedException) {
            _events.tryEmit(PatrolEvent.Error(e.message ?: getString(R.string.svc_err_not_mock_app)))
            return
        }
        acquireWakeLock()
        val at = state.value.position ?: sim.current().position
        mock.pushRaw(at, accuracyM = 5f)
        home?.let { recordJump(it, at, "virtual position") }
        prefs.suspendedAtMs = 0L
        // Back on the mock means the permission is back too (checked above): the lost-permission notice is stale.
        if (mockOpLost) { mockOpLost = false; notifications.cancelMockPermissionLost(); _state.update { it.copy(mockAppSelected = true) } }
        // The CPU may have slept meanwhile: the first tick back must not walk a clamped multi-second step.
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        val before = phaseBeforeReal
        phaseBeforeReal = null
        // The pad floats over Google Maps too: a cruise re-locked meanwhile must not walk off right after the jump.
        _joystick.update { it.withCruise(false) }
        val next = RealMode.phaseOnLeave(before, _joystick.value.enabled)
        when {
            next == PatrolPhase.MANUAL -> enterManual()
            before == PatrolPhase.MANUAL -> { _state.update { it.copy(phase = PatrolPhase.WALKING) }; leaveManual() }
            else -> {
                _state.update { it.copy(phase = next) }
                // onWaypointsChanged skips SUSPENDED: a list edited meanwhile is taken up now, as it would have been then.
                if ((next == PatrolPhase.WALKING || next == PatrolPhase.DWELLING || next == PatrolPhase.PAUSED) && waypoints != planWaypoints) {
                    if (waypoints.isEmpty()) beginReturnHome() else replanRemaining(reason = "waypoints edited on the real position")
                }
            }
        }
        // The 繼續 NectarRunner sent meanwhile for the run it had paused the patrol for.
        if (resumeOnLeave && state.value.phase == PatrolPhase.PAUSED) setPaused(false)
        resumeOnLeave = false
        lastNotificationMs = 0
        _events.tryEmit(PatrolEvent.SwitchedToVirtual)
        Log.i(TAG, "virtual position back at $at → ${state.value.phase}")
        health("VIRTUAL POSITION back (${state.value.phase}, ${MockHealthLog.zoneNote()})")
    }

    /** 回家／立刻前往 on the real position would move an avatar nobody sees. */
    private fun refuseWhileReal() {
        Log.i(TAG, "refused: on the real position")
        _events.tryEmit(PatrolEvent.Error(getString(R.string.svc_err_suspended)))
    }

    // ------------------------------------------------------------------ keeping the mock in place (MockGuard, 2026-10-08)

    private var lastInteractive: Boolean? = null
    private var lastOpCheckMs: Long? = null
    /** The system took the mock-location permission away mid-patrol (seen in PLAN N6); the notification is up. */
    private var mockOpLost = false
    private val healthLastMs = HashMap<String, Long>()
    private val healthSuppressed = HashMap<String, Int>()

    /** Engine thread, once per tick while the mock must hold. */
    private fun guardMock(nowMs: Long, gapMs: Long, interactive: Boolean) {
        val phase = state.value.phase
        if (!MockGuard.guarding(phase)) { lastInteractive = null; return }
        val wake = if (wakeLock?.isHeld == true) "held" else "NOT held"
        if (lastInteractive != null && lastInteractive != interactive) health("SCREEN ${onOff(interactive)} ($phase)")
        lastInteractive = interactive
        if (gapMs >= MockGuard.TICK_GAP_LOG_MS) {
            health("TICK GAP ${gapMs / 1000}s - the engine did not tick (process frozen, CPU asleep or a slow step write): $phase, screen ${onOff(interactive)}, wakelock $wake", key = "gap")
        }
        if (MockGuard.due(nowMs, lastOpCheckMs, MockGuard.OP_CHECK_MS)) {
            lastOpCheckMs = nowMs
            val allowed = mock.isMockAppSelected()
            if (!allowed && !mockOpLost) {
                mockOpLost = true
                health("MOCK APP PERMISSION LOST: android:mock_location is no longer allowed ($phase, screen ${onOff(interactive)}, ${MockHealthLog.zoneNote()})")
                _state.update { it.copy(mockAppSelected = false) }
                notifications.mockPermissionLost()
                _events.tryEmit(PatrolEvent.Error(getString(R.string.svc_err_mock_permission_lost)))
            } else if (allowed && mockOpLost) {
                mockOpLost = false
                health("MOCK APP PERMISSION BACK")
                _state.update { it.copy(mockAppSelected = true) }
                notifications.cancelMockPermissionLost()
                // Still guarding = no leak was seen meanwhile (that would have gone to 真實位置): mend it before the game asks.
                // Play services left mock mode when it was taken away; the providers may be gone too.
                mock.requestRepair("mock app permission back", providers = true)
            }
        }
        mock.repairIfNeeded(nowMs, MockGuard.REPAIR_MIN_GAP_MS)?.let { health("REPAIR: $it", key = "repair") }
    }

    /**
     * A fix the system handed out that is not ours (MockLocationController's passive watch). Far from the avatar while
     * guarding it is a leak: the game may already have seen the real position.
     *
     * Then the patrol goes to 真實位置 and stays there (MockGuard.afterLeak). Pulling it back to the virtual position
     * would be a jump the app makes by itself, and if the cause persists it repeats: Taiwan ⇄ Japan all night, far more
     * suspicious than one stay on the real position and one jump back when the user wakes up and taps 回到虛擬位置
     * (2026-10-08: "反覆外洩又立刻回去…更容易被偵測出定位異常"). Only problems the game has not seen yet (a push that
     * failed, the permission coming back) are still mended silently, in guardMock.
     */
    private fun onForeignFix(provider: String, fix: LatLng, accuracyM: Float) {
        val s = state.value
        if (!mock.isRunning || pendingFinish != null) return
        val virtual = s.position ?: return
        if (!MockGuard.isLeak(fixIsMock = false, phase = s.phase, fix = fix, virtual = virtual, recentPushes = mock.recentPushes())) return
        val d = GeoMath.distanceM(fix, virtual)
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        health(
            "LEAK via $provider: a real fix ${LocationJump.distanceText(d)} from the avatar (±${accuracyM.toInt()} m) - ${s.phase}, " +
                "screen ${onOff(interactive)}, wakelock ${if (wakeLock?.isHeld == true) "held" else "NOT held"}, " +
                "mock app ${if (mock.isMockAppSelected()) "allowed" else "DENIED"}, ${MockHealthLog.zoneNote()} -> staying on the real position",
        )
        if (MockGuard.afterLeak(s.phase) != PatrolPhase.SUSPENDED) return
        // The jump the game saw is to where the leaked fix put it; enterRealMode records its own (to home) right after.
        enterRealMode(PatrolEvent.REASON_LEAK)
        recordJump(virtual, fix, "leak via $provider")
        notifications.leakSwitchedToReal(LocationJump.distanceText(d))
    }

    private fun resetGuard() {
        lastInteractive = null
        lastOpCheckMs = null
        if (mockOpLost) notifications.cancelMockPermissionLost()
        mockOpLost = false
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    /**
     * One line in MockHealthLog. With a [key], the same kind of line at most once per MockGuard.LOG_REPEAT_MS; the
     * ones in between are counted into the next. Keyed calls: engine thread only.
     */
    private fun health(text: String, key: String? = null) {
        if (key != null) {
            val now = SystemClock.elapsedRealtime()
            if (!MockGuard.due(now, healthLastMs[key], MockGuard.LOG_REPEAT_MS)) {
                healthSuppressed[key] = (healthSuppressed[key] ?: 0) + 1
                return
            }
            healthLastMs[key] = now
            val skipped = healthSuppressed.remove(key) ?: 0
            if (skipped > 0) { MockHealthLog.append(this, "$text (+$skipped like it in the last minute)"); return }
        }
        MockHealthLog.append(this, text)
    }

    // ------------------------------------------------------------------ 9c: Google Maps → ask about 真實位置 (opt-in)

    private var lastForeground: String? = null
    private var lastForegroundPollMs = 0L

    /** Polls who is in front every [FOREGROUND_POLL_MS] while the screen is on; only ever asks, never switches or resumes. */
    private fun maybeAskRealForMaps(nowMs: Long) {
        // Off: forget who was in front, or switching it on again would compare with an app seen long ago and miss the first Maps.
        if (!prefs.autoRealForMaps) { lastForeground = null; return }
        if (nowMs - lastForegroundPollMs < FOREGROUND_POLL_MS) return
        lastForegroundPollMs = nowMs
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        val end = System.currentTimeMillis()
        val current = ForegroundWatch.foreground(resumedBetween(end - 2 * FOREGROUND_POLL_MS, end), lastForeground)
        // Ask, never switch by itself (2026-10-08): reading a shop's reviews needs no real GPS, and a switch is a jump.
        if (ForegroundWatch.shouldAskForReal(lastForeground, current, state.value.phase)) {
            val snapshot = state.value
            health("GOOGLE MAPS in front: asked whether to switch to the real position")
            lifecycleScope.launch(Dispatchers.Main) { MapsPrompt.ask(this@PatrolService, snapshot) }
        } else if (ForegroundWatch.leftMaps(lastForeground, current)) {
            lifecycleScope.launch(Dispatchers.Main) { MapsPrompt.dismiss(this@PatrolService) }
        }
        lastForeground = current
    }

    @Suppress("DEPRECATION")   // MOVE_TO_FOREGROUND == ACTIVITY_RESUMED (API 29); minSdk is 28
    private fun resumedBetween(fromMs: Long, toMs: Long): List<AppEvent> = runCatching {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = usm.queryEvents(fromMs, toMs)
        val e = UsageEvents.Event()
        val out = ArrayList<AppEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) out += AppEvent(e.packageName, e.timeStamp)
        }
        out
    }.getOrElse { emptyList() }

    /** Immediate failure before the tick loop exists (start path). Safe from any thread. */
    private fun failNow(message: String, silent: Boolean = false) {
        Log.w(TAG, "fail: $message")
        if (!silent) health("FAIL: $message")
        _state.update { it.copy(phase = PatrolPhase.STOPPING, lastError = if (silent) null else message) }
        if (!silent) {
            _events.tryEmit(PatrolEvent.Error(message))
            notifications.error(message)
        }
        lifecycleScope.launch(engine) {
            tickJob?.cancel()
            mock.stop()
            withContext(Dispatchers.Main) { teardown(if (silent) null else message) }
        }
    }

    private fun teardown(keepError: String? = null) {
        releaseWakeLock()
        // Every path through here is a clean exit: the game is (or is about to be) on real GPS,
        // so there is nothing to resume from.
        PatrolCheckpoint.clear(this)
        prefs.suspendedAtMs = 0L
        val last = state.value
        prefs.lastPosition = last.position
        // A vehicle is a one-session thing; the next patrol starts on foot. Same for the joystick.
        _travelOverride.value = null
        _joystick.value = JoystickInput()
        _state.value = PatrolState(
            home = last.home ?: prefs.home,
            lastError = keepError,
            stepsWrittenToday = last.stepsWrittenToday,
            mockAppSelected = mock.isMockAppSelected(),
            healthConnectReady = steps.isAvailable,
        )
        stopRequested = false
        pendingFinish = null
        replanPending = false
        teleportTargetId = null
        stayAtId = null
        returnOnResume = false
        resetGuard()
        phaseBeforeReal = null
        resumeOnLeave = false
        lastForeground = null      // the next patrol must not inherit this one's "who was in front"
        MapsPrompt.dismiss(this)   // main thread here: no question outlives the patrol
        lastNotificationKey = null // ...nor believe its notification is still up: stopForeground below removes it
        doneThisLap.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun maybeUpdateNotification() {
        val now = SystemClock.elapsedRealtime()
        val s = state.value
        if (s.phase == PatrolPhase.IDLE || s.phase == PatrolPhase.STOPPING) return
        if (lastNotificationMs != 0L && now - lastNotificationMs < NOTIFICATION_INTERVAL_MS) return   // cheap check first
        val key = runCatching { notifications.ongoingKey(s) }.getOrElse { return }   // as before T14, building the text must not fail the tick
        if (!NotificationGate.shouldPost(now, lastNotificationMs, lastNotificationKey, key, NOTIFICATION_INTERVAL_MS)) return
        lastNotificationMs = now
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(PatrolNotifications.ID_ONGOING, notifications.ongoing(s))
        }.onSuccess { lastNotificationKey = key }   // a post that threw never counts as shown: it is tried again after the gap
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PikminGPS:patrol").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        tickJob?.cancel()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (state.value.phase != PatrolPhase.IDLE) {
            health("SERVICE DESTROYED while ${state.value.phase} (task swiped or killed): the mock goes, the game sees the real GPS")
            // Killed without a clean stop (task swipe, system kill): never leave mock providers behind.
            mock.stop()
            _state.value = PatrolState(home = prefs.home, mockAppSelected = mock.isMockAppSelected(), healthConnectReady = steps.isAvailable)
        }
        releaseWakeLock()
        // Its "the mock comes back by itself once allowed" no longer holds without a patrol.
        if (mockOpLost) notifications.cancelMockPermissionLost()
        engine.close()
        super.onDestroy()
    }

    companion object {
        const val TAG = "PikminGPS"
        private const val PKG = "app.pikminbloom.gps"
        const val ACTION_START = "$PKG.action.START"
        const val ACTION_PAUSE = "$PKG.action.PAUSE"
        const val ACTION_RESUME = "$PKG.action.RESUME"
        const val ACTION_RETURN_HOME = "$PKG.action.RETURN_HOME"
        const val ACTION_STOP = "$PKG.action.STOP"
        const val ACTION_SKIP_WAYPOINT = "$PKG.action.SKIP_WAYPOINT"
        const val ACTION_GO_TO = "$PKG.action.GO_TO"
        const val ACTION_RESUME_CHECKPOINT = "$PKG.action.RESUME_CHECKPOINT"
        const val EXTRA_THEN_RETURN_HOME = "then_return_home"
        const val ACTION_RETURN_HOME_DEFAULT = "$PKG.action.RETURN_HOME_DEFAULT"
        const val ACTION_RESEED_STEPS = "$PKG.action.RESEED_STEPS"
        const val ACTION_SWITCH_TO_REAL = "$PKG.action.SWITCH_TO_REAL"
        const val ACTION_SWITCH_TO_VIRTUAL = "$PKG.action.SWITCH_TO_VIRTUAL"
        const val EXTRA_TELEPORT = "teleport"
        /** 前往後停在這裡: pause on arrival at the flower gone to (start or 立刻前往). */
        const val EXTRA_STAY = "stay_on_arrival"
        const val EXTRA_HOME_LAT = "home_lat"
        const val EXTRA_HOME_LON = "home_lon"
        const val EXTRA_START_AT_INDEX = "start_at_index"
        const val EXTRA_WAYPOINT_INDEX = "waypoint_index"

        /** At most one ongoing-notification re-post per this long, and only when its text changed (NotificationGate). */
        private const val NOTIFICATION_INTERVAL_MS = 15_000L
        private const val SETTLE_TICKS = 3

        /** Shorter than this and a lap has no walking in it, so repeating it would just spin. */
        private const val MIN_LAP_M = 5.0
        private const val CHECKPOINT_INTERVAL_MS = 5_000L
        private const val FOREGROUND_POLL_MS = 2_000L

        /** Settings that matter while a patrol runs (everything Prefs.config() reads). */
        private val LIVE_CONFIG_KEYS = setOf(
            Prefs.KEY_SPEED_KMH, Prefs.KEY_STRIDE_CM, Prefs.KEY_LOOP_MODE,
            Prefs.KEY_SPEED_CAR_KMH, Prefs.KEY_SPEED_HIGHWAY_KMH, Prefs.KEY_SPEED_PLANE_KMH,
            Prefs.KEY_ORBIT, Prefs.KEY_INJECT_STEPS, Prefs.KEY_STEP_FLUSH_SEC, Prefs.KEY_DAILY_STEP_CAP,
            Prefs.KEY_NOTIFY_ARRIVAL, Prefs.KEY_VIBRATE_ARRIVAL, Prefs.KEY_ALERT_GAP_SEC,
            Prefs.KEY_AUTO_RETURN_LAPS,
        )

        private val _state = MutableStateFlow(PatrolState())
        val state: StateFlow<PatrolState> = _state
        private val _events = MutableSharedFlow<PatrolEvent>(extraBufferCapacity = 32)
        val events: SharedFlow<PatrolEvent> = _events

        /** Live vehicle override; null = walk at the configured speed. Survives until the patrol ends. */
        private val _travelOverride = MutableStateFlow<TravelMode?>(null)
        val travelOverride: StateFlow<TravelMode?> = _travelOverride

        /** What the floating joystick reports; the engine reads it every tick while MANUAL. */
        private val _joystick = MutableStateFlow(JoystickInput())
        val joystick: StateFlow<JoystickInput> = _joystick

        val isRunning: Boolean get() = _state.value.phase != PatrolPhase.IDLE

        /** The override choices offered in the UI, in cycling order (the bike left 2026-10-08: TravelMode.BIKE). */
        val OVERRIDE_CHOICES: List<TravelMode?> = listOf(null, TravelMode.CAR, TravelMode.HIGHWAY, TravelMode.PLANE)

        fun setTravelOverride(mode: TravelMode?) {
            _travelOverride.value = mode
        }

        /** Next choice after the current one (overlay button cycles through them). */
        fun cycleTravelOverride(): TravelMode? {
            val i = OVERRIDE_CHOICES.indexOf(_travelOverride.value)
            val next = OVERRIDE_CHOICES[(i + 1) % OVERRIDE_CHOICES.size]
            _travelOverride.value = next
            return next
        }

        fun setJoystickEnabled(enabled: Boolean) {
            _joystick.update { if (enabled) it.copy(enabled = true) else JoystickInput() }
        }

        /** Pad input; [magnitude] 0 = finger lifted (the heading is kept for 定速巡航). */
        fun steer(bearingDeg: Double, magnitude: Double) {
            _joystick.update { it.steer(bearingDeg, magnitude) }
        }

        /** 定速巡航 on/off (the pad's lock button). */
        fun setCruise(on: Boolean) {
            _joystick.update { it.withCruise(on) }
        }

        fun intent(context: Context, action: String): Intent =
            Intent(context, PatrolService::class.java).setAction(action)

        /** Starts the patrol; [homeOverride] reuses a known real position instead of a fresh fix. */
        fun start(context: Context, homeOverride: LatLng? = null, startAtIndex: Int = 0, teleport: Boolean = false, stay: Boolean = false) {
            // startForeground(type = location) throws without this on API 34+, and a service that
            // then stops without going foreground takes the whole process down. Refuse here instead.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "start refused: no location permission")
                _events.tryEmit(PatrolEvent.Error(context.getString(R.string.svc_err_no_location_permission)))
                return
            }
            val i = intent(context, ACTION_START).putExtra(EXTRA_START_AT_INDEX, startAtIndex).putExtra(EXTRA_TELEPORT, teleport).putExtra(EXTRA_STAY, stay)
            if (homeOverride != null) i.putExtra(EXTRA_HOME_LAT, homeOverride.lat).putExtra(EXTRA_HOME_LON, homeOverride.lon)
            ContextCompat.startForegroundService(context, i)
        }

        private fun send(context: Context, action: String, configure: (Intent.() -> Unit)? = null) {
            // Only meaningful while the service runs. Starting it just to have onStartCommand stop
            // it again is a process crash (see the IDLE branch there), so drop the command instead.
            if (state.value.phase == PatrolPhase.IDLE) {
                Log.i(TAG, "send $action ignored: not running")
                return
            }
            val i = intent(context, action)
            configure?.invoke(i)
            runCatching { ContextCompat.startForegroundService(context, i) }
                .onFailure { Log.w(TAG, "send $action failed: ${it.message}") }
        }

        /** Continue an interrupted patrol from its checkpoint; must be called from a visible activity. */
        fun resumeFromCheckpoint(context: Context, thenReturnHome: Boolean) {
            val i = intent(context, ACTION_RESUME_CHECKPOINT).putExtra(EXTRA_THEN_RETURN_HOME, thenReturnHome)
            ContextCompat.startForegroundService(context, i)
        }

        /** NectarRunner reports here so the overlay / main screen show it like any other event. */
        fun emitNectar(flowerName: String, result: NectarResult) {
            val ok = result is NectarResult.Collected
            val detail = if (result is NectarResult.Failed) "${result.stage}: ${result.reason}" else "ok"
            _events.tryEmit(PatrolEvent.Nectar(flowerName, ok, detail))
        }

        /** After 重設今日寫入的步數: re-read what Health Connect holds so the daily cap opens again. */
        fun reseedSteps(context: Context) = send(context, ACTION_RESEED_STEPS)

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        /** 真實位置: every app sees the real GPS; the patrol waits where it is. */
        fun switchToReal(context: Context) = send(context, ACTION_SWITCH_TO_REAL)
        /** 回到虛擬位置: the mock comes back at the kept position (a jump - only on the user's tap). */
        fun switchToVirtual(context: Context) = send(context, ACTION_SWITCH_TO_VIRTUAL)
        /** 回家: [dest] null = the real position (mock released on arrival); a saved home parks there. [teleport] jumps (per use). */
        fun returnHome(context: Context, dest: LatLng? = null, teleport: Boolean = false) = send(context, ACTION_RETURN_HOME) {
            if (dest != null) putExtra(EXTRA_HOME_LAT, dest.lat).putExtra(EXTRA_HOME_LON, dest.lon)
            putExtra(EXTRA_TELEPORT, teleport)
        }
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun skipWaypoint(context: Context) = send(context, ACTION_SKIP_WAYPOINT)

        /** 立刻前往: head for waypoint [index] of the active route next. [stay] = 前往後停在這裡 (pause on arrival). */
        fun goTo(context: Context, index: Int, teleport: Boolean = false, stay: Boolean = false) =
            send(context, ACTION_GO_TO) { putExtra(EXTRA_WAYPOINT_INDEX, index); putExtra(EXTRA_TELEPORT, teleport); putExtra(EXTRA_STAY, stay) }
    }
}
