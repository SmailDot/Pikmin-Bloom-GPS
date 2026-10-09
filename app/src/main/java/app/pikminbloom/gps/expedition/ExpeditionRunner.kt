package app.pikminbloom.gps.expedition

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import app.pikminbloom.gps.PikminGpsApp
import app.pikminbloom.gps.R
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
 * screenshots and taps from [NectarAccessibilityService], one toast with the result, and an ongoing notification
 * whose Stop action cancels the run (the bar is hidden during a run, so the notification is the way to stop it).
 */
object ExpeditionRunner {
    private const val TAG = "PikminGPS"
    private const val NOTICE_ID = 22
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /**
     * Starts a run and returns at once. [setBarHidden] is called on the main thread: true before the first tap,
     * so the bar never sits over the game's taps, and false when the run ends, however it ends.
     * Does nothing when a run is already going or the accessibility service is off.
     */
    fun start(context: Context, target: ExpeditionTarget, maxDispatch: Int, setBarHidden: (Boolean) -> Unit) {
        val app = context.applicationContext
        if (isBusy) return
        val svc = NectarAccessibilityService.instance
        if (svc == null) { Log.i(TAG, "expedition: skipped, accessibility service off"); return }
        job = scope.launch {
            val session = ExpeditionSession(io(svc), log = { Log.i(TAG, "expedition: $it") })
            showNotice(app)
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
                cancelNotice(app)
                // NonCancellable: after a cancel the bar must still come back and the result must still show.
                withContext(NonCancellable + Dispatchers.Main) {
                    setBarHidden(false)
                    showResult(app, stop, count)
                }
            }
        }
    }

    /**
     * The run's notification: ongoing and quiet, with a Stop action that broadcasts to [ExpeditionStopReceiver].
     * Posting is best-effort (the user may have turned notifications off); the run does not depend on it.
     */
    private fun showNotice(app: Context) {
        val stop = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, ExpeditionStopReceiver::class.java).setAction(ExpeditionStopReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notice = NotificationCompat.Builder(app, PikminGpsApp.CHANNEL_EXPEDITION)
            .setSmallIcon(R.drawable.ic_flower)
            .setContentTitle(app.getString(R.string.expedition_running))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, app.getString(R.string.expedition_stop), stop)
            .build()
        val nm = app.getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTICE_ID, notice) }
    }

    private fun cancelNotice(app: Context) {
        app.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID)
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
