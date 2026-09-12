package app.pikminbloom.gps.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.vision.FlowerScanPlan.PlannedFlower
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The bird's-eye Big Flower scan: ties [ScreenCaptureService] (frames), [PatrolService] (the
 * simulated position at the moment of each frame), [FlowerDetector] and [ScanTracker] together and
 * publishes one [ScanState] for the UI.
 *
 * Process-local singleton like the services' companions. `start` needs a running
 * [ScreenCaptureService] (the projection consent can only be obtained by an Activity) and a patrol
 * that is moving or paused; the patrol's own walking provides the calibration baseline, so the user
 * never has to do anything but open the game's 俯瞰模式 and tap its recenter button.
 *
 * Only reads the screen. Never injects touches.
 */
object FlowerScanner {

    sealed class ScanState {
        data object Idle : ScanState()

        /** `start` was called without a projection: the UI must obtain consent first. */
        data object NeedProjection : ScanState()

        /**
         * Frames are arriving but are not usable; [reason] is a user-facing zh-TW line. [blank] marks
         * the one case the user must fix by hand (the game blanked itself against the capture and
         * has to be relaunched), so the UI can explain it properly instead of in one truncated line.
         */
        data class WaitingForBirdsEye(val reason: String, val blank: Boolean = false) : ScanState()

        data class Calibrating(val framesSoFar: Int, val metresSoFar: Double) : ScanState()

        data class Scanning(val calibration: Calibration, val found: List<PlannedFlower>) : ScanState()

        data class Done(val found: List<PlannedFlower>) : ScanState()

        data class Error(val message: String) : ScanState()

        /** The flowers found so far, whatever the state. */
        val foundFlowers: List<PlannedFlower>
            get() = when (this) {
                is Scanning -> found
                is Done -> found
                else -> emptyList()
            }

        val isActive: Boolean
            get() = this is WaitingForBirdsEye || this is Calibrating || this is Scanning
    }

    private const val TAG = "PikminGPS"

    /** Time between frames. Detection on a 1220x2712 frame costs a few hundred ms of one core. */
    private const val INTERVAL_MS = 2_000L

    /** Consecutive capture failures before the scan gives up. */
    private const val MAX_CAPTURE_FAILURES = 5

    /**
     * How long the walker stays paused before a stationary capture. The game eases its avatar
     * toward each fix over a few seconds; eight is comfortably past that on the test device.
     */
    private const val SETTLE_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state

    private var job: Job? = null
    @Volatile private var tracker: ScanTracker? = null

    /**
     * How many of the found flowers the user has already seen in a results dialog; the UI shows
     * the dialog again only when the list has grown past this.
     */
    @Volatile var reviewedCount: Int = 0

    val isRunning: Boolean get() = job?.isActive == true

    /** Flowers found so far (empty when nothing has been found or the results were discarded). */
    val found: List<PlannedFlower> get() = _state.value.foundFlowers

    /** The calibration in force, for the UI / logs. */
    val calibration: Calibration? get() = tracker?.calibration

    /**
     * Starts the scan loop. Requires [ScreenCaptureService] to be running; publishes
     * [ScanState.NeedProjection] otherwise so the caller can obtain the consent first.
     */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (isRunning) {
            Log.i(TAG, "scan already running")
            return
        }
        if (!ScreenCaptureService.isRunning.value) {
            Log.w(TAG, "scan start without a projection")
            _state.value = ScanState.NeedProjection
            return
        }
        val prefs = Prefs(app)
        val saved = prefs.scanCalibration?.takeIf {
            ScanTracker.isSavedUsable(it, prefs.scanCalibrationSavedAtMs, System.currentTimeMillis())
        }
        if (saved != null) Log.i(TAG, "scan: saved calibration candidate $saved (will be validated)")
        val t = ScanTracker(savedCalibration = saved)
        tracker = t
        reviewedCount = 0
        _state.value = ScanState.Calibrating(0, 0.0)
        job = scope.launch { runLoop(app, prefs, t) }
        Log.i(TAG, "scan started")
    }

    /** Cancels the loop and releases the projection (the cast icon goes away). */
    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        val j = job
        job = null
        j?.cancel()
        ScreenCaptureService.stop(app)
        val found = tracker?.found.orEmpty()
        _state.value = if (found.isEmpty()) ScanState.Idle else ScanState.Done(found)
        Log.i(TAG, "scan stopped: ${found.size} flower(s) found")
    }

    /** Forget the results of a finished scan (after they were added or dismissed). */
    @Synchronized
    fun discardResults() {
        if (isRunning) return
        tracker = null
        reviewedCount = 0
        _state.value = ScanState.Idle
    }

    private suspend fun runLoop(app: Context, prefs: Prefs, tracker: ScanTracker) {
        val detector = FlowerDetector()
        var captureFailures = 0
        var frames = 0
        // When the patrol entered PAUSED (a user pause); a frame taken well after that is settled.
        var pausedSinceMs = 0L
        try {
            while (currentCoroutineContext().isActive) {
                if (!ScreenCaptureService.isRunning.value) {
                    finish(app, ScanState.Error(ScreenCaptureService.lastError ?: app.getString(R.string.scan_err_projection_stopped)))
                    return
                }
                val patrol = PatrolService.state.value
                when (patrol.phase) {
                    PatrolPhase.STARTING -> {
                        publishWaiting(app.getString(R.string.scan_wait_patrol))
                        delay(1_000L)
                        continue
                    }
                    PatrolPhase.IDLE, PatrolPhase.STOPPING -> {
                        Log.i(TAG, "scan: patrol ended, finishing")
                        finish(app, doneState(tracker))
                        return
                    }
                    else -> Unit
                }
                val pos = patrol.position
                if (pos == null) {
                    delay(1_000L)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val frozen = patrol.phase == PatrolPhase.PAUSED || patrol.phase == PatrolPhase.PARKED
                if (frozen) {
                    if (pausedSinceMs == 0L) pausedSinceMs = now
                } else {
                    pausedSinceMs = 0L
                }

                // The scanner never pauses the patrol. Reference frames are simply taken on the
                // move (ScanTracker promotes a frame to a reference whenever it wants one); the
                // avatar's easing lag costs a few percent of scale over a 40 m baseline, which the
                // error budget absorbs, whereas stop-starting the walker every 20 m was both
                // conspicuous in the game and confusing to watch. A pause that IS in effect is the
                // user's own and simply yields a settled frame.
                val stationary = frozen
                val settled = stationary && pausedSinceMs != 0L && SystemClock.elapsedRealtime() - pausedSinceMs >= SETTLE_MS

                // Position and frame are read back to back so they describe the same instant.
                val posNow = PatrolService.state.value.position ?: pos
                val t0 = SystemClock.elapsedRealtime()
                val frame = ScreenCaptureService.captureFrame()
                if (frame == null) {
                    captureFailures++
                    Log.w(TAG, "scan: capture failed ($captureFailures/$MAX_CAPTURE_FAILURES)")
                    if (captureFailures >= MAX_CAPTURE_FAILURES) {
                        finish(app, ScanState.Error(app.getString(R.string.scan_err_capture_failed)))
                        return
                    }
                    delay(INTERVAL_MS)
                    continue
                }
                captureFailures = 0
                frames++
                val result = detector.detect(frame)
                val t1 = SystemClock.elapsedRealtime()
                val outcome = tracker.onFrame(
                    result, frame.width, frame.height, posNow,
                    blank = ScanTracker.isMostlyBlack(frame),
                    stationary = settled,
                )
                Log.i(
                    TAG,
                    "scan frame $frames ${frame.width}x${frame.height} hits=${result.hits.size} " +
                        "avatars=${result.avatars.size} badges=${result.badges.size} pos=$posNow " +
                        (if (settled) "STATIONARY " else "") + "${t1 - t0} ms -> ${describe(outcome)}",
                )
                // stop() may have run during detect(); never publish over its final state.
                currentCoroutineContext().ensureActive()
                publish(app, prefs, outcome)
                delay(INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "scan loop crashed", t)
            finish(app, ScanState.Error(t.message ?: t.javaClass.simpleName))
        }
    }

    private fun publish(app: Context, prefs: Prefs, outcome: ScanTracker.Outcome) {
        when (outcome) {
            ScanTracker.Outcome.WrongView -> publishWaiting(app.getString(R.string.scan_wait_wrong_view))
            is ScanTracker.Outcome.Blank -> {
                if (outcome.exhausted) publishWaiting(app.getString(R.string.scan_wait_blank), blank = true)
            }
            is ScanTracker.Outcome.Empty -> {
                if (outcome.exhausted) publishWaiting(app.getString(R.string.scan_wait_no_flowers))
                // Otherwise keep showing the previous progress; a single empty frame is just a pan.
            }
            is ScanTracker.Outcome.Calibrating ->
                _state.value = ScanState.Calibrating(outcome.usableFrames, outcome.metresSoFar)
            is ScanTracker.Outcome.Scanning -> {
                outcome.calibrationChanged?.let { attempt ->
                    val cal = outcome.calibration
                    Log.i(
                        TAG,
                        "CALIBRATION metresPerPixel=${fmt(cal.metresPerPixel, 4)} " +
                            "screenNorthDeg=${fmt(cal.screenNorthDeg, 1)} matches=${attempt.matches.size} " +
                            "residual=${fmt(attempt.residualPx, 1)} px " +
                            "translation=${attempt.translationPx?.let { "(${fmt(it.x, 1)},${fmt(it.y, 1)})" }} " +
                            (if (outcome.adoptedSaved) "adopted-saved " else "measured ") + attempt.reason,
                    )
                    // A measured calibration is worth keeping for the next session; an adopted one
                    // is already stored.
                    if (!outcome.adoptedSaved) prefs.scanCalibration = cal
                }
                for (f in outcome.newlyFound) {
                    Log.i(
                        TAG,
                        "FLOWER ${f.waypoint.name} lat=${fmt(f.waypoint.lat, 6)} lon=${fmt(f.waypoint.lon, 6)} " +
                            "range=${fmt(f.rangeM, 0)} m stem=${f.stemFound} merged=${f.mergedCount}",
                    )
                }
                _state.value = ScanState.Scanning(outcome.calibration, outcome.found)
            }
        }
    }

    private fun publishWaiting(reason: String, blank: Boolean = false) {
        val cur = _state.value
        if (cur is ScanState.WaitingForBirdsEye && cur.reason == reason) return
        _state.value = ScanState.WaitingForBirdsEye(reason, blank)
    }

    private fun doneState(tracker: ScanTracker): ScanState =
        if (tracker.found.isEmpty()) ScanState.Idle else ScanState.Done(tracker.found)

    /** Orderly end from inside the loop: release the projection and publish the final state. */
    private fun finish(app: Context, state: ScanState) {
        synchronized(this) {
            job = null
            ScreenCaptureService.stop(app)
            _state.value = state
        }
        Log.i(TAG, "scan finished: ${describe(state)}")
    }

    private fun describe(o: ScanTracker.Outcome): String = when (o) {
        ScanTracker.Outcome.WrongView -> "wrong view"
        is ScanTracker.Outcome.Blank -> "blank x${o.streak}"
        is ScanTracker.Outcome.Empty -> "empty x${o.streak}"
        is ScanTracker.Outcome.Calibrating -> "calibrating frames=${o.usableFrames} moved=${fmt(o.metresSoFar, 0)} m"
        is ScanTracker.Outcome.Scanning -> "scanning found=${o.found.size} new=${o.newlyFound.size} planPos=${o.planPos}"
    }

    private fun describe(s: ScanState): String = when (s) {
        ScanState.Idle -> "idle"
        ScanState.NeedProjection -> "need projection"
        is ScanState.WaitingForBirdsEye -> "waiting: ${s.reason}"
        is ScanState.Calibrating -> "calibrating ${s.framesSoFar} frames, ${fmt(s.metresSoFar, 0)} m"
        is ScanState.Scanning -> "scanning, ${s.found.size} found"
        is ScanState.Done -> "done, ${s.found.size} found"
        is ScanState.Error -> "error: ${s.message}"
    }

    private fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)
}
