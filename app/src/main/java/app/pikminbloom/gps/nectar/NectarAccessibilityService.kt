package app.pikminbloom.gps.nectar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import app.pikminbloom.gps.vision.RgbImage
import app.pikminbloom.gps.vision.fromBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What only an accessibility service can do here: inject taps and swipes into the game, and take one-off
 * screenshots of its screen (takeScreenshot, never a recording). It never reads window content, events are
 * filtered to the game and ignored, and it is off unless the user enables it in system settings. It exists for
 * 自動拉花 and 自動探險.
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

    /**
     * One screenshot of the game's screen for 自動探險, or null when none can be had: Android below 11, or the
     * system refused twice. A single still image, never a recording; the game paints its screens black while
     * the screen is being recorded, which is why the scan's MediaProjection is not used here.
     *
     * Privacy: the pixels exist only in memory, as the frame of one step of a run. They are never written to
     * disk, logged or sent anywhere, and the frame is dropped once the step has read it.
     */
    suspend fun screenshot(): RgbImage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            takeOne()
        } catch (e: ScreenshotFailed) {
            // The system allows one screenshot per ~333 ms; a request inside that window fails. Wait it out, once.
            if (e.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return null
            delay(SCREENSHOT_RETRY_MS)
            try {
                takeOne()
            } catch (again: ScreenshotFailed) {
                null
            }
        }
    }

    /** One takeScreenshot call. Resumes with the frame (null if the copy failed), or throws [ScreenshotFailed]. */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeOne(): RgbImage? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val frame = runCatching { toRgbImage(screenshot) }
                    .onFailure { Log.w(TAG, "screenshot copy failed", it) }
                    .getOrNull()
                cont.resume(frame)
            }

            override fun onFailure(errorCode: Int) {
                cont.resumeWithException(ScreenshotFailed(errorCode))
            }
        })
    }

    /** Copies the system's screenshot into an [RgbImage], then releases the hardware buffer and each bitmap made on the way. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun toRgbImage(screenshot: ScreenshotResult): RgbImage? {
        val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
        try {
            val software = hardware?.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            try {
                return RgbImage.fromBitmap(software)
            } finally {
                software.recycle()
            }
        } finally {
            hardware?.recycle()
            screenshot.hardwareBuffer.close()
        }
    }

    /** takeScreenshot reported an error; [code] is one of AccessibilityService.ERROR_TAKE_SCREENSHOT_*. */
    private class ScreenshotFailed(val code: Int) : Exception("takeScreenshot failed with error $code")

    companion object {
        private const val TAG = "PikminGPS"
        private const val TAP_MS = 60L
        private const val SCREENSHOT_RETRY_MS = 400L

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
