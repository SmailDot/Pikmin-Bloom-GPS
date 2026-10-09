package app.pikminbloom.gps.expedition

import android.content.Context
import android.util.Log
import android.widget.Toast
import app.pikminbloom.gps.R
import app.pikminbloom.gps.auto.AutoRuns
import app.pikminbloom.gps.auto.RunNotice
import app.pikminbloom.gps.nectar.NectarAccessibilityService
import app.pikminbloom.gps.nectar.NectarIo
import app.pikminbloom.gps.vision.RgbImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Glue for 自動探險, like [app.pikminbloom.gps.nectar.NectarRunner]: one run at a time on a background scope,
 * screenshots and taps from [NectarAccessibilityService], one toast with the result, and the shared run notification
 * ([RunNotice]) whose Stop action cancels whichever auto run is busy. The bar is hidden during a run.
 */
object ExpeditionRunner {
    private const val TAG = "PikminGPS"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /**
     * Starts a run and returns at once. [setBarHidden] is called on the main thread: true before the first tap,
     * so the bar never sits over the game's taps, and false when the run ends, however it ends.
     * Does nothing when an auto run is already going or the accessibility service is off.
     */
    fun start(context: Context, target: ExpeditionTarget, maxDispatch: Int, setBarHidden: (Boolean) -> Unit) {
        val app = context.applicationContext
        if (AutoRuns.isBusy) return
        val svc = NectarAccessibilityService.instance
        if (svc == null) { Log.i(TAG, "expedition: skipped, accessibility service off"); return }
        job = scope.launch {
            val session = ExpeditionSession(io(svc), log = { Log.i(TAG, "expedition: $it") })
            RunNotice.show(app, R.string.expedition_running)
            var stop = ExpeditionStop.CANCELLED
            var count = 0
            try {
                withContext(Dispatchers.Main) { setBarHidden(true) }
                val result = session.run(target, maxDispatch)
                stop = result.stop
                count = result.dispatched
            } catch (e: CancellationException) {
                count = session.dispatchedSoFar
                throw e
            } finally {
                RunNotice.cancel(app)
                // NonCancellable: after a cancel the bar must still come back and the result must still show.
                withContext(NonCancellable + Dispatchers.Main) {
                    setBarHidden(false)
                    showResult(app, stop, count)
                }
            }
        }
    }

    /** Stops the run now. The result toast still reports how many expeditions went out before the stop. */
    fun cancel() {
        job?.cancel()
    }

    private fun io(svc: NectarAccessibilityService): NectarIo = object : NectarIo {
        override suspend fun frame(): RgbImage? = svc.screenshot()
        override suspend fun tap(x: Int, y: Int) = svc.tap(x, y)
        override suspend fun swipe(x: Int, fromY: Int, toY: Int, durationMs: Long) = svc.swipe(x, fromY, toY, durationMs)
        override suspend fun back() = svc.back()
        override suspend fun wait(ms: Long) = delay(ms)
    }

    private fun showResult(context: Context, stop: ExpeditionStop, count: Int) {
        val reason = context.getString(reasonText(stop))
        Toast.makeText(context, context.getString(R.string.expedition_result, count, reason), Toast.LENGTH_LONG).show()
    }

    private fun reasonText(stop: ExpeditionStop): Int = when (stop) {
        ExpeditionStop.DONE_END_OF_LIST -> R.string.expedition_reason_end
        ExpeditionStop.LIMIT_REACHED -> R.string.expedition_reason_limit
        ExpeditionStop.NO_PIKMIN -> R.string.expedition_reason_no_pikmin
        ExpeditionStop.NOT_ON_LIST -> R.string.expedition_reason_not_list
        ExpeditionStop.LOST -> R.string.expedition_reason_lost
        ExpeditionStop.CANCELLED -> R.string.expedition_reason_cancelled
    }
}
