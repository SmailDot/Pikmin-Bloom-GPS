package app.pikminbloom.gps.vision

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pikminbloom.gps.PikminGpsApp
import app.pikminbloom.gps.R
import app.pikminbloom.gps.ui.MainActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Foreground service (type `mediaProjection`) that mirrors the display into an [ImageReader] and
 * hands single frames out as [RgbImage]s on request.
 *
 * ## Why MediaProjection and not an AccessibilityService
 * An enabled accessibility service is visible to every app on the phone without any permission
 * (`AccessibilityManager.getEnabledAccessibilityServiceList()`), which makes it a cheap detection
 * vector. A media projection is a per-session consent the user grants in a system dialog, shows a
 * cast icon in the status bar while it runs, and is invisible to the app being captured.
 *
 * ## Contract with the platform (Android 14+)
 *  1. The consent `Intent` from [MediaProjectionManager.createScreenCaptureIntent] is obtained by
 *     an Activity and passed here whole; the token inside it can be used exactly once.
 *  2. `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` MUST run before
 *     [MediaProjectionManager.getMediaProjection], or the platform throws.
 *  3. A [MediaProjection.Callback] MUST be registered before [MediaProjection.createVirtualDisplay].
 *  4. One projection may back one virtual display; after `stop()` a new consent is required.
 *
 * ## Frame delivery
 * The reader keeps two buffers. The newest frame is held open (never more than one) and swapped
 * whenever a fresher one arrives, so [captureFrame] is served from what is on screen *now* even
 * when the game has not repainted since the request. Conversion to [RgbImage] happens on the
 * request, on the capture thread, so idle mirroring costs nothing but the buffer swap.
 *
 * This service only READS the screen. It never dispatches input.
 */
class ScreenCaptureService : Service() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var windowManager: WindowManager
    private val thread = HandlerThread("PikminGPS-capture")
    private lateinit var handler: Handler

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    // Capture thread only.
    private var latest: Image? = null
    private var pending: CancellableContinuation<RgbImage?>? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameDpi = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked the cast from the status bar, or the system (HyperOS) tore it down.
            Log.i(TAG, "media projection stopped by the system/user")
            lastError = getString(R.string.scan_err_projection_stopped)
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.i(TAG, "captured content resized to ${width}x$height")
            handler.post { recreateIfDisplayChanged() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        windowManager = getSystemService(WindowManager::class.java)
        thread.start()
        handler = Handler(thread.looper)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (projection != null) {
                    Log.i(TAG, "screen capture already running")
                } else {
                    val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                    @Suppress("DEPRECATION")
                    val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                    if (code == Int.MIN_VALUE || data == null) {
                        Log.w(TAG, "screen capture start without a consent result")
                        lastError = getString(R.string.scan_err_projection_denied)
                        stopSelf()
                    } else {
                        startCapture(code, data)
                    }
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "screen capture stop requested")
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post { recreateIfDisplayChanged() }
    }

    override fun onDestroy() {
        release()
        instance = null
        _isRunning.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        thread.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start / stop

    private fun startCapture(resultCode: Int, data: Intent) {
        lastError = null
        // 2. Foreground with the media-projection type BEFORE touching the projection token.
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground(mediaProjection) failed", t)
            lastError = getString(R.string.scan_err_foreground, t.message ?: t.javaClass.simpleName)
            stopSelf()
            return
        }

        val p = try {
            projectionManager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed", t)
            null
        }
        if (p == null) {
            lastError = getString(R.string.scan_err_projection_denied)
            stopSelf()
            return
        }
        projection = p
        try {
            // 3. Callback first, then the display.
            p.registerCallback(projectionCallback, handler)
            createDisplay(p)
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            lastError = getString(R.string.scan_err_display, t.message ?: t.javaClass.simpleName)
            stopSelf()
            return
        }
        _isRunning.value = true
        Log.i(TAG, "screen capture started ${frameWidth}x$frameHeight @ $frameDpi dpi")
    }

    private fun createDisplay(p: MediaProjection) {
        val (w, h, dpi) = displaySize()
        frameWidth = w
        frameHeight = h
        frameDpi = dpi
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
        r.setOnImageAvailableListener({ onImageAvailable(it) }, handler)
        reader = r
        virtualDisplay = p.createVirtualDisplay(
            "PikminGPS-scan", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
    }

    /**
     * Rotation or a fold changes the display size; a reader of the old size would then receive
     * frames with a different geometry. Recreate it in place rather than tearing the projection
     * down, which would need a new consent. Capture thread only.
     */
    private fun recreateIfDisplayChanged() {
        val vd = virtualDisplay ?: return
        val (w, h, dpi) = displaySize()
        if (w == frameWidth && h == frameHeight) return
        Log.i(TAG, "display changed ${frameWidth}x$frameHeight -> ${w}x$h, recreating the reader")
        try {
            latest?.close()
            latest = null
            val old = reader
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
            r.setOnImageAvailableListener({ onImageAvailable(it) }, handler)
            reader = r
            frameWidth = w
            frameHeight = h
            frameDpi = dpi
            vd.resize(w, h, dpi)
            vd.surface = r.surface
            old?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "reader recreate failed", t)
            lastError = getString(R.string.scan_err_display, t.message ?: t.javaClass.simpleName)
            stopSelf()
        }
    }

    private fun release() {
        val p = projection
        projection = null
        runCatching { virtualDisplay?.release() }.onFailure { Log.w(TAG, "release display failed", it) }
        virtualDisplay = null
        runCatching { p?.unregisterCallback(projectionCallback) }
        // The projection must stop, or the cast icon stays in the status bar after the scan.
        runCatching { p?.stop() }.onFailure { Log.w(TAG, "projection stop failed", it) }
        val r = reader
        reader = null
        // The held image and the reader belong to the capture thread; close them there if it is
        // still alive, otherwise (destroy path after quitSafely) right here.
        val closeAll = Runnable {
            runCatching { latest?.close() }
            latest = null
            runCatching { r?.close() }
            pending?.let { c -> pending = null; if (c.isActive) c.resume(null) }
        }
        if (!thread.isAlive || !handler.post(closeAll)) closeAll.run()
        if (p != null) Log.i(TAG, "screen capture released")
    }

    // ------------------------------------------------------------------ frames (capture thread)

    private fun onImageAvailable(r: ImageReader) {
        if (r !== reader) {
            // A stale reader from before a resize; drain it so it can be closed.
            runCatching { r.acquireLatestImage()?.close() }
            return
        }
        val img = try {
            r.acquireLatestImage()
        } catch (t: Throwable) {
            Log.w(TAG, "acquireLatestImage failed", t)
            null
        } ?: return
        latest?.close()
        latest = img
        val cont = pending
        if (cont != null) {
            pending = null
            if (cont.isActive) cont.resume(convert(img))
        }
    }

    private fun convert(img: Image): RgbImage? = try {
        RgbImage.fromImage(img)
    } catch (t: Throwable) {
        Log.w(TAG, "frame conversion failed", t)
        null
    }

    /** Resolves with the newest frame; waits for the first one if none has arrived yet. */
    private suspend fun grab(): RgbImage? = suspendCancellableCoroutine { cont ->
        val posted = handler.post {
            val img = latest
            if (img != null) {
                if (cont.isActive) cont.resume(convert(img))
            } else {
                pending?.let { old -> if (old.isActive) old.resume(null) }
                pending = cont
            }
        }
        if (!posted && cont.isActive) cont.resume(null)
        cont.invokeOnCancellation { handler.post { if (pending === cont) pending = null } }
    }

    // ------------------------------------------------------------------ helpers

    private fun displaySize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, PikminGpsApp.CHANNEL_SCREEN_SCAN)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(getString(R.string.scan_notif_title))
            .setContentText(getString(R.string.scan_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.scan_notif_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "PikminGPS"
        private const val PKG = "app.pikminbloom.gps"
        const val ACTION_START = "$PKG.action.SCREEN_CAPTURE_START"
        const val ACTION_STOP = "$PKG.action.SCREEN_CAPTURE_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val MAX_IMAGES = 2

        /** Distinct from PatrolNotifications' ids (1..4). */
        const val NOTIFICATION_ID = 21

        @Volatile private var instance: ScreenCaptureService? = null

        private val _isRunning = MutableStateFlow(false)
        /** True from the moment the virtual display exists until the projection is released. */
        val isRunning: StateFlow<Boolean> = _isRunning

        /** Why the last start or the last session ended, for the scanner's error state. */
        @Volatile var lastError: String? = null
            private set

        /**
         * Starts the projection with the consent result of
         * [MediaProjectionManager.createScreenCaptureIntent]. Must be called promptly after the
         * consent (the token expires) and from a visible activity.
         */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            runCatching { ContextCompat.startForegroundService(context, i) }
                .onFailure { Log.w(TAG, "startForegroundService(ScreenCaptureService) failed", it) }
        }

        /** Releases the projection (the cast icon disappears) and stops the service. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ScreenCaptureService::class.java)) }
                .onFailure { Log.w(TAG, "stopService(ScreenCaptureService) failed", it) }
        }

        /**
         * The newest on-screen frame as an [RgbImage], or null when the capture is not running, no
         * frame arrived within [timeoutMs], or the conversion failed. Never throws; safe from any
         * coroutine. Each call copies ~13 MB on a 1220x2712 display, so do not call it in a tight loop.
         */
        suspend fun captureFrame(timeoutMs: Long = 3_000L): RgbImage? {
            val svc = instance ?: return null
            if (!_isRunning.value) return null
            return try {
                withTimeoutOrNull(timeoutMs) { svc.grab() }
            } catch (t: Throwable) {
                Log.w(TAG, "captureFrame failed", t)
                null
            }
        }
    }
}
