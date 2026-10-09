package app.pikminbloom.gps.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The Stop action on [RunNotice]: cancels whichever auto run is busy. Declared with exported=false in the manifest,
 * so only this app's own PendingIntent reaches it.
 */
class AutoRunStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) AutoRuns.cancel()
    }

    companion object {
        const val ACTION_STOP = "app.pikminbloom.gps.auto.STOP"
    }
}
