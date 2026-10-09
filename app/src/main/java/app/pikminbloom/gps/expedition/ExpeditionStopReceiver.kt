package app.pikminbloom.gps.expedition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The Stop action on the 自動探險 notification ([ExpeditionRunner] posts it). Declared with exported=false in the
 * manifest, so only this app's own PendingIntent reaches it.
 */
class ExpeditionStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) ExpeditionRunner.cancel()
    }

    companion object {
        const val ACTION_STOP = "app.pikminbloom.gps.expedition.STOP"
    }
}
