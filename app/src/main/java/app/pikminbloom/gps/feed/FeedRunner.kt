package app.pikminbloom.gps.feed

import android.content.Context
import android.util.Log
import android.widget.Toast
import app.pikminbloom.gps.R
import app.pikminbloom.gps.auto.AutoRuns
import app.pikminbloom.gps.auto.RunNotice
import app.pikminbloom.gps.nectar.NectarAccessibilityService
import app.pikminbloom.gps.support.AutoRunLog
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
import java.util.Locale

/**
 * Glue for 自動餵精華, like [app.pikminbloom.gps.expedition.ExpeditionRunner]: one run at a time on a background
 * scope, screenshots and gestures from [NectarAccessibilityService], the shared run notification ([RunNotice]), the
 * bar hidden during the run, and one toast with the result.
 */
object FeedRunner {
    private const val TAG = "PikminGPS"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /**
     * Starts a run of [rounds] feed rounds and returns at once. [setBarHidden] is called on the main thread: true
     * before the first gesture, false when the run ends, however it ends. Does nothing when an auto run is already
     * going or the accessibility service is off.
     */
    fun start(context: Context, rounds: Int, setBarHidden: (Boolean) -> Unit) {
        val app = context.applicationContext
        if (AutoRuns.isBusy) return
        val svc = NectarAccessibilityService.instance
        if (svc == null) { Log.i(TAG, "feed: skipped, accessibility service off"); return }
        // The navigation bar is read once per run: the game is drawn above it on 3-button phones.
        val bar = svc.bottomInsetFraction()
        job = scope.launch {
            AutoRunLog.startRun(app, "feed", "target=$rounds, navInset=${"%.3f".format(Locale.ROOT, bar)}")
            val session = FeedSession(io(svc), bottomInsetFraction = bar, log = { Log.i(TAG, "feed: $it"); AutoRunLog.append(app, it) })
            RunNotice.show(app, R.string.feed_running)
            var stop = FeedStop.CANCELLED
            var count = 0
            try {
                withContext(Dispatchers.Main) { setBarHidden(true) }
                val result = session.run(rounds)
                stop = result.stop
                count = result.rounds
            } catch (e: CancellationException) {
                count = session.roundsSoFar
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

    /** Stops the run now. The result toast still reports how many rounds were fed before the stop. */
    fun cancel() {
        job?.cancel()
    }

    private fun io(svc: NectarAccessibilityService): FeedIo = object : FeedIo {
        override suspend fun frame(): RgbImage? = svc.screenshot()
        override suspend fun pinch(w: Int, h: Int): Boolean = svc.pinch(w, h)
        override suspend fun dragHoldCircle(fromX: Int, fromY: Int, toX: Int, toY: Int, radius: Int, holdMs: Long): Boolean =
            svc.dragHoldCircle(fromX, fromY, toX, toY, radius, holdMs)
        override suspend fun path(points: List<Pair<Int, Int>>, durationMs: Long): Boolean = svc.path(points, durationMs)
        override suspend fun wait(ms: Long) = delay(ms)
    }

    private fun showResult(context: Context, stop: FeedStop, count: Int) {
        val reason = context.getString(reasonText(stop))
        Toast.makeText(context, context.getString(R.string.feed_result, count, reason), Toast.LENGTH_LONG).show()
    }

    private fun reasonText(stop: FeedStop): Int = when (stop) {
        FeedStop.DONE -> R.string.feed_reason_done
        FeedStop.NOT_ON_FEED -> R.string.feed_reason_not_feed
        FeedStop.NO_BLOOM -> R.string.feed_reason_no_bloom
        FeedStop.LOST -> R.string.feed_reason_lost
        FeedStop.CANCELLED -> R.string.feed_reason_cancelled
    }
}
