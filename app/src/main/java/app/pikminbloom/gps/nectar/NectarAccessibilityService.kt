package app.pikminbloom.gps.nectar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The one thing only an accessibility service can do: inject taps and swipes into another app.
 * It reads nothing (no window content, events filtered to the game and ignored) and is off unless
 * the user enables it in system settings. It exists for 自動拉花 only.
 *
 * Detection note (PLAN D1/E3): any app can list enabled accessibility services without a
 * permission, so keeping this enabled is a visible signal to the game. The switch in Settings
 * says so; the user decides.
 */
class NectarAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "nectar accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }
        if (!dispatchGesture(g, cb, null) && cont.isActive) cont.resume(false)
    }

    suspend fun tap(x: Int, y: Int): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, TAP_MS)).build())
    }

    suspend fun swipe(x: Int, fromY: Int, toY: Int, durationMs: Long): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), fromY.toFloat()); lineTo(x.toFloat(), toY.toFloat()) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durationMs)).build())
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    companion object {
        private const val TAG = "PikminGPS"
        private const val TAP_MS = 60L

        @Volatile var instance: NectarAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w(TAG, "accessibility settings failed", it) }
        }
    }
}
