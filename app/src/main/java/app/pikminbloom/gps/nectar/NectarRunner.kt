package app.pikminbloom.gps.nectar

import android.content.Context
import android.os.PowerManager
import android.util.Log
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.service.PatrolEvent
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.vision.FlowerDetector
import app.pikminbloom.gps.vision.PixelPoint
import app.pikminbloom.gps.vision.RgbImage
import app.pikminbloom.gps.vision.ScanTracker
import app.pikminbloom.gps.vision.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Glue for 自動拉花: on arrival at a Big Flower, if the switch is on and both the capture and the
 * accessibility service are up, freeze the avatar (pause), run [NectarCollector] on the live
 * screen, then walk on. The game must be in front, in bird's-eye view; if it is not, step 1 finds
 * no flower and nothing is tapped.
 */
object NectarRunner {
    private const val TAG = "PikminGPS"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /** Called by PatrolService when a waypoint circle is entered. Returns at once. */
    fun onArrived(context: Context, flowerName: String) {
        val app = context.applicationContext
        if (!Prefs(app).autoNectar) return
        if (!ScreenCaptureService.isRunning.value) { Log.i(TAG, "nectar: skipped, no screen capture"); return }
        if (!NectarAccessibilityService.isEnabled) { Log.i(TAG, "nectar: skipped, accessibility service off"); return }
        if (isBusy) { Log.i(TAG, "nectar: skipped, still busy"); return }
        // The capture cannot see a dark screen, and pausing the patrol first would leave it paused: a paused avatar with
        // the screen off lets the CPU sleep, so the run and the resume after it could wait for hours. Nothing is paused here.
        if (app.getSystemService(PowerManager::class.java)?.isInteractive == false) { Log.i(TAG, "nectar: skipped, screen off"); return }
        job = scope.launch {
            PatrolService.pause(app)
            delay(SETTLE_BEFORE_MS)     // the avatar and the map stop moving
            val result = runCollector()
            PatrolService.resume(app)
            PatrolService.emitNectar(flowerName, result)
        }
    }

    /** Debug driver / manual button: collect right now without touching the patrol. */
    fun collectNow(context: Context, onDone: (NectarResult) -> Unit = {}) {
        if (isBusy) return
        job = scope.launch {
            val r = runCollector()
            onDone(r)
        }
    }

    private suspend fun runCollector(): NectarResult {
        val svc = NectarAccessibilityService.instance
            ?: return NectarResult.Failed(NectarStage.FIND_FLOWER, "accessibility service off")
        val detector = FlowerDetector()
        val io = object : NectarIo {
            override suspend fun frame(): RgbImage? = ScreenCaptureService.captureFrame()
            override suspend fun tap(x: Int, y: Int) = svc.tap(x, y)
            override suspend fun swipe(x: Int, fromY: Int, toY: Int, durationMs: Long) = svc.swipe(x, fromY, toY, durationMs)
            override suspend fun back() = svc.back()
            override suspend fun wait(ms: Long) = delay(ms)
        }
        val first = io.frame() ?: return NectarResult.Failed(NectarStage.FIND_FLOWER, "no frame")
        val det = detector.detect(first)
        // Where the avatar is on screen: the detector's avatar blob nearest the usual spot, else that spot.
        val avatar: PixelPoint = ScanTracker.playerPixel(det.avatars, first.width, first.height)
        val collector = NectarCollector(io, detect = { detector.detect(it).hits }, log = { Log.i(TAG, "nectar: $it") })
        val r = collector.collect(avatar)
        Log.i(TAG, "nectar: $r")
        return r
    }

    /** Arrival fires at the circle edge; every extra second walks ~5 m closer to the flower and the avatar's own marker. */
    private const val SETTLE_BEFORE_MS = 600L
}
