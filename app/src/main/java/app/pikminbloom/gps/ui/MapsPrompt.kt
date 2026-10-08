package app.pikminbloom.gps.ui

import android.content.Context
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.service.PatrolNotifications

/**
 * Asks, on top of Google Maps, whether to switch to 真實位置 (service/ForegroundWatch; it used to switch by itself).
 * An overlay dialog when the app may draw over others (it does for the floating bar), else a notification whose
 * action opens the main screen's own confirmation. Main thread only.
 */
object MapsPrompt {
    private var dialog: AlertDialog? = null

    fun ask(context: Context, state: PatrolState) {
        dismiss(context)
        if (!Permissions.canDrawOverlays(context)) {
            PatrolNotifications(context).askRealForMaps()
            return
        }
        val themed = ContextThemeWrapper(context, R.style.Theme_PikminGps)
        val d = PatrolDialogs.confirmRealForMaps(themed, state) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } ?: return
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        d.setOnDismissListener { if (dialog === d) dialog = null }
        try {
            d.show()
            dialog = d
        } catch (t: RuntimeException) {
            // BadTokenException / SecurityException: the overlay permission went away meanwhile.
            Log.w("PikminGPS", "cannot show the Maps question as an overlay", t)
            PatrolNotifications(context).askRealForMaps()
        }
    }

    /** Maps left the front, the patrol switched or ended: the question is moot. */
    fun dismiss(context: Context) {
        runCatching { dialog?.dismiss() }
        dialog = null
        PatrolNotifications(context).cancelAskRealForMaps()
    }
}
