package app.pikminbloom.gps.auto

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import app.pikminbloom.gps.PikminGpsApp
import app.pikminbloom.gps.R

/**
 * The ongoing notification every auto run shows while it goes: the run's title, and one Stop action that cancels
 * whichever run is busy ([AutoRunStopReceiver]). The bar is hidden during a run, so this is how it is stopped.
 * Posting is best-effort: the user may have turned notifications off, and a run does not depend on it.
 */
object RunNotice {
    private const val ID = 22

    fun show(app: Context, @StringRes title: Int) {
        val stop = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, AutoRunStopReceiver::class.java).setAction(AutoRunStopReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notice = NotificationCompat.Builder(app, PikminGpsApp.CHANNEL_EXPEDITION)
            .setSmallIcon(R.drawable.ic_flower)
            .setContentTitle(app.getString(title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, app.getString(R.string.expedition_stop), stop)
            .build()
        runCatching { app.getSystemService(NotificationManager::class.java).notify(ID, notice) }
    }

    fun cancel(app: Context) {
        app.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
