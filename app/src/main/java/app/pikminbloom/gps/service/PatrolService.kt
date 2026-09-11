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
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.ReturnMode
import app.pikminbloom.gps.data.TravelMode
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * Foreground service that runs the whole patrol: captures the real position ("home"), installs the
 * mock providers, ticks the [WalkSimulator] once a second, flushes steps to Health Connect and walks
 * back home on request.
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
    private var home: LatLng? = null

    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTickElapsedMs = 0L
    private var lastNotificationMs = 0L

    // Step accounting (engine thread only).
    private var stepsAccrued = 0.0
    private var stepsFlushed = 0L
    private var distanceSinceFlush = 0.0
    private var flushWindowStart: Instant = Instant.now()
    private var stepsWrittenToday = 0L
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
            // Stale notification action / nothing running: discharge the start obligation and go away.
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_PAUSE -> lifecycleScope.launch(engine) { setPaused(true) }
            ACTION_RESUME -> lifecycleScope.launch(engine) { setPaused(false) }
            ACTION_RETURN_HOME -> lifecycleScope.launch(engine) { beginReturnHome() }
            ACTION_STOP -> lifecycleScope.launch(engine) { requestStop() }
            ACTION_SKIP_WAYPOINT -> lifecycleScope.launch(engine) { skipWaypoint() }
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
        _state.value = PatrolState(phase = PatrolPhase.STARTING, mockAppSelected = mock.isMockAppSelected())
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
        val startIndex = intent.getIntExtra(EXTRA_START_AT_INDEX, 0).coerceIn(0, waypoints.size - 1)
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
                prefs.home = h
                stepsWrittenToday = if (config.injectSteps && steps.isAvailable) steps.stepsWrittenByUsToday() else 0L
                if (stopRequested) { failNow(getString(R.string.svc_phase_stopping), silent = true); return@launch }
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
                lastNotificationMs = 0
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
     */
    private fun handleResumeCheckpoint(thenReturnHome: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _events.tryEmit(PatrolEvent.Error(getString(R.string.svc_err_no_location_permission)))
            stopSelf(); return
        }
        if (!goForeground()) return
        if (state.value.phase != PatrolPhase.IDLE) { Log.i(TAG, "resume ignored: already ${state.value.phase}"); return }
        val cp = PatrolCheckpoint.resumable(this) ?: run { failNow(getString(R.string.svc_err_no_checkpoint)); return }

        _state.value = PatrolState(phase = PatrolPhase.STARTING, mockAppSelected = mock.isMockAppSelected())
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
                mock.pushRaw(resumeAt, accuracyM = 5f, altitudeM = config.altitudeM)

                home = cp.home
                prefs.home = cp.home
                sim = WalkSimulator(config)
                lap = cp.lap
                stepsAccrued = cp.stepsAccrued
                stepsFlushed = cp.stepsFlushed
                distanceSinceFlush = cp.distanceSinceFlush
                flushWindowStart = Instant.ofEpochMilli(cp.flushWindowStartMs)
                stepsWrittenToday = cp.stepsWrittenToday
                lastArrivalAlertMs = 0L

                val goHome = thenReturnHome || cp.phase == PatrolPhase.RETURNING_HOME || waypoints.isEmpty()
                if (goHome) {
                    plan = PatrolPlanner.planReturnHome(resumeAt, cp.home, routeTravelMode.takeIf { it != TravelMode.WALK })
                    sim.load(plan)
                    settleTicks = 0
                } else {
                    // Continue with the remaining waypoints of the interrupted lap, from where we are.
                    val order = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
                    val pos = order.indexOf(cp.targetWaypointIndex.coerceIn(0, waypoints.size - 1))
                    val remaining = if (pos >= 0) order.drop(pos) else order
                    plan = PatrolPlanner.planLap(resumeAt, waypoints, config, remaining.ifEmpty { order }, lap)
                    sim.load(plan)
                }

                _state.update {
                    it.copy(
                        phase = if (goHome) PatrolPhase.RETURNING_HOME else phaseFor(plan.segments.firstOrNull()?.kind ?: SegmentKind.TRAVEL),
                        home = cp.home, position = resumeAt, startedAtMs = cp.startedAtMs,
                        distanceWalkedM = cp.distanceWalkedM, sessionSteps = floor(stepsAccrued).toLong(),
                        stepsWrittenToday = stepsWrittenToday, lapsCompleted = cp.lapsCompleted,
                        mockAppSelected = true, healthConnectReady = steps.isAvailable, lastError = null,
                        currentWaypointIndex = if (goHome) -1 else cp.targetWaypointIndex,
                        currentWaypointName = if (goHome) getString(R.string.svc_target_home) else waypoints.getOrNull(cp.targetWaypointIndex)?.name,
                        distanceToTargetM = if (goHome) GeoMath.distanceM(resumeAt, cp.home) else 0.0,
                    )
                }
                Log.i(TAG, "resumed from checkpoint (${cp.ageMs / 1000}s old) at $resumeAt, goHome=$goHome")
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
        if (s.phase == PatrolPhase.IDLE || s.phase == PatrolPhase.STOPPING || s.phase == PatrolPhase.STARTING) return
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
        if (lap == 0 && startIndex > 0) order = order.drop(startIndex)

        val mode = routeTravelMode
        val first = order.firstOrNull()?.let { waypoints.getOrNull(it) }
        plan = if (lap == 0 && mode != TravelMode.WALK && first != null) {
            // A trip: cover the (possibly long) leg to the first place at vehicle speed - no steps,
            // no planting - then wander it on foot for its dwell time regardless of the global
            // orbit setting, because that walk is the whole point of going there. Any further
            // waypoints are walked as usual from where the wander ends.
            val trip = PatrolPlanner.planTripTo(
                from, first.latLng, config, mode,
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
        sim.load(plan)
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
        val dt = ((nowMs - lastTickElapsedMs) / 1000.0).coerceIn(0.0, 3.0)
        lastTickElapsedMs = nowMs

        when (state.value.phase) {
            PatrolPhase.PAUSED -> mock.push(sim.current())
            PatrolPhase.WALKING, PatrolPhase.DWELLING -> {
                val s = sim.advance(dt)
                mock.push(s)
                account(s)
                if (s.arrivedAtWaypoint != null) onArrived(s.arrivedAtWaypoint)
                _state.update {
                    it.copy(
                        phase = phaseFor(s.kind), position = s.position, speedMps = s.speedMps,
                        currentWaypointIndex = s.waypointIndex ?: it.currentWaypointIndex,
                        currentWaypointName = s.waypointIndex?.let { i -> waypoints.getOrNull(i)?.name } ?: it.currentWaypointName,
                        distanceToTargetM = distanceToTarget(s),
                    )
                }
                if (s.lapFinished) onLapFinished(s)
            }
            PatrolPhase.RETURNING_HOME -> {
                val h = home ?: run { pendingFinish = Finish.Stop; return }
                if (config.returnMode == ReturnMode.TELEPORT || sim.finished) {
                    mock.pushRaw(h, accuracyM = 5f, altitudeM = config.altitudeM)
                    _state.update { it.copy(position = h, speedMps = 0.0, distanceToTargetM = 0.0) }
                    settleTicks++
                    if (settleTicks >= SETTLE_TICKS) pendingFinish = Finish.ReturnedHome
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

        if (pendingFinish == null) maybeFlushSteps(force = false)
        maybeUpdateNotification()

        // Cheap insurance against a thermal kill: a few hundred bytes every few seconds.
        if (nowMs - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS) {
            lastCheckpointMs = nowMs
            writeCheckpoint()
        }
    }

    private suspend fun runFinish(finish: Finish) {
        _state.update { it.copy(phase = PatrolPhase.STOPPING) }
        runCatching { maybeFlushSteps(force = true) }.onFailure { Log.w(TAG, "final flush failed", it) }
        mock.stop()
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
        // Vehicle legs move the position but produce no steps; nobody walks while driving.
        if (s.countsSteps) {
            stepsAccrued += s.distanceDeltaM / config.strideM
            distanceSinceFlush += s.distanceDeltaM
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

    private fun onArrived(index: Int) {
        val name = waypoints.getOrNull(index)?.name ?: "#${index + 1}"
        Log.i(TAG, "arrived at $index ($name)")
        _events.tryEmit(PatrolEvent.ArrivedAtWaypoint(index, name))
        if (!config.notifyOnArrival) return
        // Rate limit: passing several flowers in a row must not turn into a burst of buzzes.
        val now = SystemClock.elapsedRealtime()
        val gapMs = config.arrivalAlertMinGapSec * 1000L
        val quiet = lastArrivalAlertMs != 0L && now - lastArrivalAlertMs < gapMs
        lastArrivalAlertMs = now
        notifications.arrived(name, vibrate = config.vibrateOnArrival && !quiet)
    }

    private fun onLapFinished(s: Sample) {
        val finishedLap = lap
        val completed = finishedLap + 1
        _events.tryEmit(PatrolEvent.LapFinished(finishedLap))
        _state.update { it.copy(lapsCompleted = completed) }
        lap++

        val limit = config.autoReturnAfterLaps
        if (limit > 0 && completed >= limit) {
            Log.i(TAG, "completed $completed lap(s), limit $limit → returning home")
            beginReturnHome()
            return
        }
        val nextOrder = PatrolPlanner.orderFor(lap, waypoints.size, config.loopMode)
        if (nextOrder.isEmpty()) {
            Log.i(TAG, "route finished (ONCE) → returning home")
            beginReturnHome()
            return
        }
        loadLap(s.position)
        // A lap with nowhere to walk (one flower and no orbiting, or flowers on top of each other)
        // would "arrive" again on the very next tick and spin the notification once a second.
        if (plan.totalLengthM < MIN_LAP_M) {
            Log.i(TAG, "lap $lap is degenerate (${"%.1f".format(plan.totalLengthM)} m) → nothing left to walk, returning home")
            beginReturnHome()
        }
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
        val room = (config.dailyStepCap - stepsWrittenToday).coerceAtLeast(0)
        // Keep the cadence humanly plausible if the user asked for a ceiling.
        val cadenceRoom = if (config.maxCadenceSpm > 0) {
            (config.maxCadenceSpm * windowSec / 60.0).toLong().coerceAtLeast(1)
        } else {
            Long.MAX_VALUE
        }
        val toWrite = minOf(pending, room, cadenceRoom)
        if (toWrite < pending) {
            Log.i(TAG, "writing $toWrite of $pending steps (daily room $room, cadence room $cadenceRoom)")
        }
        val distance = distanceSinceFlush * (toWrite.toDouble() / pending)
        val ok = if (toWrite > 0) steps.write(flushWindowStart, now, toWrite, distance) else true
        if (ok) {
            stepsWrittenToday += toWrite
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
        if (p == PatrolPhase.STARTING) { requestStop(); return }
        val h = home ?: run { requestStop(); return }
        val from = sim.current().position
        settleTicks = 0
        // A trip that was driven out is driven back: walking 20 km home would take hours, and the
        // route already declares how such legs are covered. Ordinary patrols still walk.
        val mode = routeTravelMode.takeIf { it != TravelMode.WALK }
        if (config.returnMode == ReturnMode.WALK) {
            plan = PatrolPlanner.planReturnHome(from, h, mode)
            sim.load(plan)
        }
        _state.update {
            it.copy(phase = PatrolPhase.RETURNING_HOME, currentWaypointName = getString(R.string.svc_target_home),
                distanceToTargetM = GeoMath.distanceM(from, h))
        }
        lastNotificationMs = 0
        Log.i(TAG, "returning home (${config.returnMode}, ${mode ?: TravelMode.WALK}) from $from to $h, ${"%.0f".format(GeoMath.distanceM(from, h))} m")
    }

    private fun requestStop() {
        when (state.value.phase) {
            PatrolPhase.IDLE -> stopSelf()
            PatrolPhase.STARTING -> stopRequested = true      // start coroutine checks this at its next step
            PatrolPhase.STOPPING -> Unit
            else -> if (pendingFinish == null) pendingFinish = Finish.Stop
        }
    }

    /** Immediate failure before the tick loop exists (start path). Safe from any thread. */
    private fun failNow(message: String, silent: Boolean = false) {
        Log.w(TAG, "fail: $message")
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
        pendingFinish = null
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
        const val ACTION_RESUME_CHECKPOINT = "$PKG.action.RESUME_CHECKPOINT"
        const val EXTRA_THEN_RETURN_HOME = "then_return_home"
        const val EXTRA_HOME_LAT = "home_lat"
        const val EXTRA_HOME_LON = "home_lon"
        const val EXTRA_START_AT_INDEX = "start_at_index"

        private const val NOTIFICATION_INTERVAL_MS = 5_000L
        private const val SETTLE_TICKS = 3

        /** Shorter than this and a lap has no walking in it, so repeating it would just spin. */
        private const val MIN_LAP_M = 5.0
        private const val CHECKPOINT_INTERVAL_MS = 5_000L

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

        private fun send(context: Context, action: String) {
            // The service is a running foreground service whenever these make sense; when it is not,
            // onStartCommand stops itself immediately, which also discharges the start obligation.
            runCatching { ContextCompat.startForegroundService(context, intent(context, action)) }
                .onFailure { Log.w(TAG, "send $action failed: ${it.message}") }
        }

        /** Continue an interrupted patrol from its checkpoint; must be called from a visible activity. */
        fun resumeFromCheckpoint(context: Context, thenReturnHome: Boolean) {
            val i = intent(context, ACTION_RESUME_CHECKPOINT).putExtra(EXTRA_THEN_RETURN_HOME, thenReturnHome)
            ContextCompat.startForegroundService(context, i)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun returnHome(context: Context) = send(context, ACTION_RETURN_HOME)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun skipWaypoint(context: Context) = send(context, ACTION_SKIP_WAYPOINT)
    }
}
