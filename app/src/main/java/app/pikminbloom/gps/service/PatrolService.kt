package app.pikminbloom.gps.service

import android.Manifest
import android.content.Context
import android.content.Intent
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
import app.pikminbloom.gps.data.LoopMode
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.ReturnMode
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.mock.MockNotAllowedException
import app.pikminbloom.gps.route.PatrolPlan
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.route.SegmentKind
import app.pikminbloom.gps.sim.Sample
import app.pikminbloom.gps.sim.WalkSimulator
import app.pikminbloom.gps.steps.StepInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.math.floor

/**
 * Foreground service that runs the whole patrol: captures the real position ("home"), installs the
 * mock providers, ticks the [WalkSimulator] once a second, flushes steps to Health Connect and walks
 * back home on request.
 */
class PatrolService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var store: WaypointStore
    private lateinit var mock: MockLocationController
    private lateinit var steps: StepInjector
    private lateinit var notifications: PatrolNotifications

    private var config = PatrolConfig()
    private var waypoints: List<Waypoint> = emptyList()
    private var sim = WalkSimulator(config)
    private var plan: PatrolPlan = PatrolPlan.EMPTY
    private var lap = 0
    private var home: LatLng? = null

    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTickElapsedMs = 0L
    private var lastNotificationMs = 0L

    // Step accounting (all guarded by running on the single tick coroutine).
    private var stepsAccrued = 0.0        // fractional steps since session start
    private var stepsFlushed = 0L         // integer steps already handed to Health Connect (or dropped)
    private var distanceSinceFlush = 0.0
    private var flushWindowStart: Instant = Instant.now()
    private var stepsWrittenToday = 0L
    private var settleTicks = 0
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = WaypointStore.get(this)
        mock = MockLocationController(this)
        steps = StepInjector(this)
        notifications = PatrolNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_RETURN_HOME -> beginReturnHome()
            ACTION_STOP -> requestStop()
            ACTION_SKIP_WAYPOINT -> skipWaypoint()
            else -> if (state.value.phase == PatrolPhase.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ start

    private fun handleStart(intent: Intent) {
        if (state.value.phase != PatrolPhase.IDLE) {
            Log.i(TAG, "start ignored: already ${state.value.phase}")
            return
        }
        // Must happen right away (Android 12+ gives us a few seconds) and before any suspension.
        _state.value = PatrolState(phase = PatrolPhase.STARTING, mockAppSelected = mock.isMockAppSelected())
        if (!goForeground()) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            fail(getString(R.string.svc_err_no_location_permission)); return
        }
        config = prefs.config()
        waypoints = store.load()
        if (waypoints.isEmpty()) { fail(getString(R.string.svc_err_no_waypoints)); return }
        if (!mock.isMockAppSelected()) { fail(getString(R.string.svc_err_not_mock_app)); return }

        val overrideHome = if (intent.hasExtra(EXTRA_HOME_LAT) && intent.hasExtra(EXTRA_HOME_LON)) {
            runCatching { LatLng(intent.getDoubleExtra(EXTRA_HOME_LAT, 0.0), intent.getDoubleExtra(EXTRA_HOME_LON, 0.0)) }.getOrNull()
        } else null
        val startIndex = intent.getIntExtra(EXTRA_START_AT_INDEX, 0).coerceIn(0, waypoints.size - 1)
        acquireWakeLock()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val h = overrideHome ?: mock.currentRealLocation(20_000)
                if (h == null) { withContext(Dispatchers.Main) { fail(getString(R.string.svc_err_no_home)) }; return@launch }
                home = h
                prefs.home = h
                stepsWrittenToday = if (config.injectSteps && steps.isAvailable) steps.stepsWrittenByUsToday() else 0L
                mock.start(config)   // throws MockNotAllowedException
                sim = WalkSimulator(config)
                lap = 0
                stepsAccrued = 0.0; stepsFlushed = 0; distanceSinceFlush = 0.0
                flushWindowStart = Instant.now()
                loadLap(h, startIndex)
                _state.update {
                    it.copy(
                        phase = if (plan.isEmpty) PatrolPhase.WALKING else phaseFor(plan.segments[0].kind),
                        home = h, position = h, startedAtMs = System.currentTimeMillis(),
                        stepsWrittenToday = stepsWrittenToday, mockAppSelected = true,
                        healthConnectReady = steps.isAvailable, lastError = null,
                        currentWaypointIndex = plan.segments.firstOrNull()?.waypointIndex ?: -1,
                        currentWaypointName = plan.segments.firstOrNull()?.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name },
                    )
                }
                startTicking()
            } catch (e: MockNotAllowedException) {
                withContext(Dispatchers.Main) { fail(e.message ?: getString(R.string.svc_err_not_mock_app)) }
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                withContext(Dispatchers.Main) { fail(t.message ?: "start failed") }
            }
        }
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, PatrolNotifications.ID_ONGOING, notifications.ongoing(state.value), type)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground failed", t)
        fail(t.message ?: "startForeground failed")
        false
    }

    private fun loadLap(from: LatLng, startIndex: Int = 0) {
        var order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        if (lap == 0 && startIndex > 0) order = order.drop(startIndex)
        plan = PatrolPlanner.planLap(from, waypoints, config, order, lap)
        sim.load(plan)
        Log.i(TAG, "lap $lap loaded: ${plan.segments.size} segments, ${"%.0f".format(plan.totalLengthM)} m")
    }

    // ------------------------------------------------------------------ tick loop

    private fun startTicking() {
        tickJob?.cancel()
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        tickJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    tick()
                } catch (t: Throwable) {
                    Log.e(TAG, "tick failed", t)
                    _state.update { it.copy(lastError = t.message) }
                }
                delay(config.tickMs)
            }
        }
    }

    private suspend fun tick() {
        val nowMs = SystemClock.elapsedRealtime()
        val dt = ((nowMs - lastTickElapsedMs) / 1000.0).coerceIn(0.0, 3.0)
        lastTickElapsedMs = nowMs
        val phase = state.value.phase

        when (phase) {
            PatrolPhase.PAUSED -> {
                mock.push(sim.current())
            }
            PatrolPhase.WALKING, PatrolPhase.DWELLING -> {
                val s = sim.advance(dt)
                mock.push(s)
                account(s)
                if (s.arrivedAtWaypoint != null) onArrived(s.arrivedAtWaypoint)
                val newPhase = phaseFor(s.kind)
                _state.update {
                    it.copy(
                        phase = newPhase, position = s.position, speedMps = s.speedMps,
                        currentWaypointIndex = s.waypointIndex ?: it.currentWaypointIndex,
                        currentWaypointName = s.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name } ?: it.currentWaypointName,
                        distanceToTargetM = distanceToTarget(s),
                    )
                }
                if (s.lapFinished) onLapFinished(s)
            }
            PatrolPhase.RETURNING_HOME -> {
                val h = home ?: run { finishStop(); return }
                if (config.returnMode == ReturnMode.TELEPORT || sim.finished) {
                    mock.pushRaw(h, accuracyM = 5f, altitudeM = config.altitudeM)
                    _state.update { it.copy(position = h, speedMps = 0.0, distanceToTargetM = 0.0) }
                    settleTicks++
                    if (settleTicks >= SETTLE_TICKS) { finishReturnHome() }
                } else {
                    val s = sim.advance(dt)
                    mock.push(s)
                    account(s)
                    _state.update {
                        it.copy(position = s.position, speedMps = s.speedMps, distanceToTargetM = GeoMath.distanceM(s.position, h))
                    }
                }
            }
            else -> Unit
        }

        maybeFlushSteps(force = false)
        maybeUpdateNotification()
        if (stopRequested && phase != PatrolPhase.STOPPING) finishStop()
    }

    private fun account(s: Sample) {
        if (s.distanceDeltaM <= 0.0) return
        stepsAccrued += s.distanceDeltaM / config.strideM
        distanceSinceFlush += s.distanceDeltaM
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

    private fun onArrived(index: Int) {
        val name = waypoints.getOrNull(index)?.name ?: "#${index + 1}"
        Log.i(TAG, "arrived at $index ($name)")
        _events.tryEmit(PatrolEvent.ArrivedAtWaypoint(index, name))
        if (config.notifyOnArrival) notifications.arrived(name)
    }

    private fun onLapFinished(s: Sample) {
        val finishedLap = lap
        _events.tryEmit(PatrolEvent.LapFinished(finishedLap))
        _state.update { it.copy(lapsCompleted = finishedLap + 1) }
        lap++
        val nextOrder = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        if (nextOrder.isEmpty()) {
            Log.i(TAG, "route finished (ONCE) → returning home")
            beginReturnHome()
        } else {
            loadLap(s.position)
        }
    }

    // ------------------------------------------------------------------ steps

    private suspend fun maybeFlushSteps(force: Boolean) {
        val now = Instant.now()
        val windowSec = Duration.between(flushWindowStart, now).seconds
        if (!force && windowSec < config.stepFlushIntervalSec) return
        val pending = floor(stepsAccrued).toLong() - stepsFlushed
        if (pending <= 0) { flushWindowStart = now; distanceSinceFlush = 0.0; return }

        if (!config.injectSteps || !steps.isAvailable) {
            stepsFlushed += pending; flushWindowStart = now; distanceSinceFlush = 0.0; return
        }
        val room = (config.dailyStepCap - stepsWrittenToday).coerceAtLeast(0)
        val toWrite = minOf(pending, room)
        val distance = if (pending > 0) distanceSinceFlush * (toWrite.toDouble() / pending) else 0.0
        val ok = if (toWrite > 0) steps.write(flushWindowStart, now, toWrite, distance) else true
        if (ok) {
            stepsWrittenToday += toWrite
            stepsFlushed += pending           // steps beyond the cap are dropped on purpose
            flushWindowStart = now
            distanceSinceFlush = 0.0
            _state.update { it.copy(stepsWrittenToday = stepsWrittenToday) }
            if (toWrite > 0) _events.tryEmit(PatrolEvent.StepsWritten(toWrite, stepsWrittenToday))
        } else if (windowSec > 45 * 60) {
            // Health Connect keeps failing: drop the oversized window rather than write a 1 h blob later.
            Log.w(TAG, "dropping $pending steps: Health Connect unavailable for ${windowSec}s")
            stepsFlushed += pending; flushWindowStart = now; distanceSinceFlush = 0.0
        }
    }

    // ------------------------------------------------------------------ control

    private fun setPaused(paused: Boolean) {
        val p = state.value.phase
        if (paused && (p == PatrolPhase.WALKING || p == PatrolPhase.DWELLING)) {
            _state.update { it.copy(phase = PatrolPhase.PAUSED, speedMps = 0.0) }
        } else if (!paused && p == PatrolPhase.PAUSED) {
            _state.update { it.copy(phase = phaseFor(sim.current().kind)) }
        }
        lastNotificationMs = 0
    }

    private fun skipWaypoint() {
        val p = state.value.phase
        if (p != PatrolPhase.WALKING && p != PatrolPhase.DWELLING && p != PatrolPhase.PAUSED) return
        val cur = sim.current()
        val idx = cur.waypointIndex ?: return
        val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        val pos = order.indexOf(idx)
        val rest = if (pos >= 0) order.drop(pos + 1) else emptyList()
        if (rest.isEmpty()) { onLapFinished(cur); return }
        plan = PatrolPlanner.planLap(cur.position, waypoints, config, rest, lap)
        sim.load(plan)
    }

    private fun beginReturnHome() {
        val p = state.value.phase
        if (p == PatrolPhase.IDLE || p == PatrolPhase.RETURNING_HOME || p == PatrolPhase.STOPPING) return
        val h = home ?: return requestStop()
        val from = sim.current().position
        settleTicks = 0
        if (config.returnMode == ReturnMode.WALK) {
            plan = PatrolPlanner.planReturnHome(from, h)
            sim.load(plan)
        }
        _state.update {
            it.copy(phase = PatrolPhase.RETURNING_HOME, currentWaypointName = getString(R.string.svc_target_home),
                distanceToTargetM = GeoMath.distanceM(from, h))
        }
        lastNotificationMs = 0
        Log.i(TAG, "returning home (${config.returnMode}) from $from to $h, ${"%.0f".format(GeoMath.distanceM(from, h))} m")
    }

    private fun finishReturnHome() {
        lifecycleScope.launch(Dispatchers.Default) {
            _state.update { it.copy(phase = PatrolPhase.STOPPING) }
            tickJob?.cancel()
            maybeFlushSteps(force = true)
            mock.stop()
            notifications.returnedHome()
            _events.tryEmit(PatrolEvent.ReturnedHome)
            withContext(Dispatchers.Main) { teardown() }
        }
    }

    private fun requestStop() {
        val p = state.value.phase
        if (p == PatrolPhase.IDLE) { stopSelf(); return }
        if (p == PatrolPhase.STARTING) { stopRequested = true; return }
        finishStop()
    }

    private fun finishStop() {
        if (state.value.phase == PatrolPhase.STOPPING) return
        _state.update { it.copy(phase = PatrolPhase.STOPPING) }
        lifecycleScope.launch(Dispatchers.Default) {
            tickJob?.cancel()
            maybeFlushSteps(force = true)
            mock.stop()
            _events.tryEmit(PatrolEvent.Stopped)
            withContext(Dispatchers.Main) { teardown() }
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, "fail: $message")
        _state.update { it.copy(phase = PatrolPhase.STOPPING, lastError = message) }
        _events.tryEmit(PatrolEvent.Error(message))
        notifications.error(message)
        lifecycleScope.launch(Dispatchers.Default) {
            tickJob?.cancel()
            mock.stop()
            withContext(Dispatchers.Main) { teardown(keepError = message) }
        }
    }

    private fun teardown(keepError: String? = null) {
        releaseWakeLock()
        val last = state.value
        prefs.lastPosition = last.position
        _state.value = PatrolState(
            home = last.home ?: prefs.home,
            lastError = keepError,
            stepsWrittenToday = last.stepsWrittenToday,
            mockAppSelected = mock.isMockAppSelected(),
            healthConnectReady = steps.isAvailable,
        )
        stopRequested = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun maybeUpdateNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationMs < NOTIFICATION_INTERVAL_MS) return
        lastNotificationMs = now
        val s = state.value
        if (s.phase == PatrolPhase.IDLE || s.phase == PatrolPhase.STOPPING) return
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(PatrolNotifications.ID_ONGOING, notifications.ongoing(s))
        }
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
        if (state.value.phase != PatrolPhase.IDLE) {
            // Killed without a clean stop (task swipe, system kill): never leave mock providers behind.
            mock.stop()
            _state.value = PatrolState(home = prefs.home, mockAppSelected = mock.isMockAppSelected(), healthConnectReady = steps.isAvailable)
        }
        releaseWakeLock()
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
        const val EXTRA_HOME_LAT = "home_lat"
        const val EXTRA_HOME_LON = "home_lon"
        const val EXTRA_START_AT_INDEX = "start_at_index"

        private const val NOTIFICATION_INTERVAL_MS = 5_000L
        private const val SETTLE_TICKS = 3

        private val _state = MutableStateFlow(PatrolState())
        val state: StateFlow<PatrolState> = _state
        private val _events = MutableSharedFlow<PatrolEvent>(extraBufferCapacity = 32)
        val events: SharedFlow<PatrolEvent> = _events

        val isRunning: Boolean get() = _state.value.phase != PatrolPhase.IDLE

        fun intent(context: Context, action: String): Intent =
            Intent(context, PatrolService::class.java).setAction(action)

        /** Starts the patrol; [homeOverride] reuses a known real position instead of a fresh fix. */
        fun start(context: Context, homeOverride: LatLng? = null, startAtIndex: Int = 0) {
            val i = intent(context, ACTION_START).putExtra(EXTRA_START_AT_INDEX, startAtIndex)
            if (homeOverride != null) i.putExtra(EXTRA_HOME_LAT, homeOverride.lat).putExtra(EXTRA_HOME_LON, homeOverride.lon)
            ContextCompat.startForegroundService(context, i)
        }

        fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
        fun resume(context: Context) = context.startService(intent(context, ACTION_RESUME))
        fun returnHome(context: Context) = context.startService(intent(context, ACTION_RETURN_HOME))
        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))
        fun skipWaypoint(context: Context) = context.startService(intent(context, ACTION_SKIP_WAYPOINT))

        @Suppress("unused")
        private val loopModes = LoopMode.entries
    }
}
