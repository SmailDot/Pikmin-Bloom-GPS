package app.pikminbloom.gps.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import app.pikminbloom.gps.PikminGpsApp
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.LocationJump
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.ui.MainActivity
import app.pikminbloom.gps.ui.RealModeCopy

/** Builds the ongoing patrol notification and the one-shot event notifications. */
class PatrolNotifications(private val ctx: Context) {

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val prefs = app.pikminbloom.gps.data.Prefs(ctx)

    fun ongoing(state: PatrolState): Notification {
        val body = text(state)
        val b = NotificationCompat.Builder(ctx, PikminGpsApp.CHANNEL_PATROL)
            .setSmallIcon(R.drawable.ic_flower)
            .setContentTitle(title(state))
            .setContentText(body.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
        when (state.phase) {
            PatrolPhase.PAUSED, PatrolPhase.PARKED -> b.addAction(0, ctx.getString(R.string.svc_action_resume), service(PatrolService.ACTION_RESUME))
            PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.MANUAL -> b.addAction(0, ctx.getString(R.string.svc_action_pause), service(PatrolService.ACTION_PAUSE))
            // A jump is never one tap (T18): this opens the app's 回到虛擬位置？ dialog; 切到真實位置 lives in the app and the bar.
            PatrolPhase.SUSPENDED -> b.addAction(0, RealModeCopy.switchLabel(suspended = true), confirmBackToVirtual())
            else -> Unit
        }
        if (state.phase != PatrolPhase.RETURNING_HOME && state.phase != PatrolPhase.STOPPING && state.phase != PatrolPhase.PARKED &&
            state.phase != PatrolPhase.SUSPENDED) {
            // No picker here, so: wherever the last 回家 went. Roaming from a saved home this means
            // that home, not the real GPS 2,000 km away.
            b.addAction(0, ctx.getString(R.string.svc_action_home), service(PatrolService.ACTION_RETURN_HOME_DEFAULT))
        }
        b.addAction(0, ctx.getString(R.string.svc_action_stop), service(PatrolService.ACTION_STOP))
        return b.build()
    }

    /**
     * Everything [ongoing] shows that can change; an equal key means there is nothing new to post
     * (NotificationGate). The phase is in it because it alone decides the buttons: every phase has its
     * own title today, but the buttons must not depend on that staying true.
     */
    fun ongoingKey(state: PatrolState): String = state.phase.name + "\n" + title(state) + "\n" + text(state)

    private fun title(state: PatrolState): String = when (state.phase) {
        PatrolPhase.IDLE -> ctx.getString(R.string.svc_phase_idle)
        PatrolPhase.STARTING -> ctx.getString(R.string.svc_phase_starting)
        PatrolPhase.WALKING -> ctx.getString(R.string.svc_phase_walking, state.currentWaypointName ?: "…")
        PatrolPhase.DWELLING -> ctx.getString(R.string.svc_phase_dwelling, state.currentWaypointName ?: "…")
        PatrolPhase.PAUSED -> ctx.getString(R.string.svc_phase_paused)
        PatrolPhase.RETURNING_HOME -> ctx.getString(R.string.svc_phase_returning, formatDistance(state.distanceToTargetM))
        PatrolPhase.STOPPING -> ctx.getString(R.string.svc_phase_stopping)
        PatrolPhase.PARKED -> ctx.getString(R.string.svc_phase_parked)
        PatrolPhase.MANUAL -> ctx.getString(R.string.svc_phase_manual)
        PatrolPhase.SUSPENDED -> ctx.getString(R.string.svc_phase_suspended)
    }

    /** The status line, plus the 上次跳躍 line below it when there is one (the expanded text; the collapsed one is its first line). */
    private fun text(state: PatrolState): String {
        val vehicle = state.travelOverride?.let { " · ${it.label} ${prefs.config().speedTextOf(it)} km/h" }.orEmpty()
        val text = ctx.getString(
            R.string.svc_status_line,
            formatDistance(state.distanceWalkedM),
            state.sessionSteps,
            state.stepsWrittenToday,
        ) + vehicle + state.stayAtName?.let { " · " + ctx.getString(R.string.status_stay_at, it) }.orEmpty()
        val jump = LocationJump.readout(prefs.lastJump, System.currentTimeMillis())
        return if (jump == null) text else "$text\n$jump"
    }

    /** @param vibrate false keeps the alert silent; the vibration motor is a real battery cost. */
    fun arrived(name: String, vibrate: Boolean) {
        notifyEvent(
            ID_ARRIVED,
            ctx.getString(R.string.svc_arrived_title, name),
            ctx.getString(R.string.svc_arrived_text),
        )
        if (vibrate) vibrate(longArrayOf(0, 220))
    }

    /** 前往後停在這裡 arrived: the one arrival always announced (the user may be asleep and want it there in the morning). */
    fun stayedAt(name: String, vibrate: Boolean) {
        notifyEvent(ID_ARRIVED, ctx.getString(R.string.svc_stayed_title, name), ctx.getString(R.string.svc_stayed_text))
        if (vibrate) vibrate(longArrayOf(0, 220))
    }

    fun returnedHome(vibrate: Boolean = true) {
        notifyEvent(ID_HOME, ctx.getString(R.string.svc_home_title), ctx.getString(R.string.svc_home_text))
        // Arriving home happens once per session, so a short double buzz is affordable.
        if (vibrate) vibrate(longArrayOf(0, 200, 120, 200))
    }

    /** Reached the user-chosen home; the mock is still on, which the text must make clear. */
    fun parkedAtHome(vibrate: Boolean) {
        notifyEvent(ID_HOME, ctx.getString(R.string.svc_parked_title), ctx.getString(R.string.svc_parked_text))
        if (vibrate) vibrate(longArrayOf(0, 200, 120, 200))
    }

    fun error(message: String) {
        notifyEvent(ID_ERROR, ctx.getString(R.string.svc_error_title), message)
    }

    fun cancelOngoing() = nm.cancel(ID_ONGOING)

    /** The system took the mock-location permission away mid-patrol (MockGuard): only the user can give it back. */
    fun mockPermissionLost() {
        notifyEvent(ID_MOCK_LOST, ctx.getString(R.string.svc_mock_lost_title), ctx.getString(R.string.svc_mock_lost_text))
    }

    fun cancelMockPermissionLost() = runCatching { nm.cancel(ID_MOCK_LOST) }

    /**
     * The Maps question (ui/MapsPrompt) when the app may not draw over Maps: tapping opens the main screen's 切到真實位置？
     * confirmation; ignoring it changes nothing.
     */
    fun askRealForMaps() {
        val n = NotificationCompat.Builder(ctx, PikminGpsApp.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_flower)
            .setContentTitle(RealModeCopy.MAPS_TITLE)
            .setContentText(ctx.getString(R.string.svc_maps_ask_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.svc_maps_ask_text)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(confirmToReal())
            .addAction(0, RealModeCopy.switchLabel(suspended = false), confirmToReal())
            .build()
        runCatching { nm.notify(ID_MAPS_ASK, n) }
    }

    fun cancelAskRealForMaps() = runCatching { nm.cancel(ID_MAPS_ASK) }

    /** The game saw the real position, so the patrol stays on it (MockGuard): say what happened and how to go back. */
    fun leakSwitchedToReal(distanceText: String) {
        notifyEvent(ID_LEAK, ctx.getString(R.string.svc_leak_title), ctx.getString(R.string.svc_leak_text, distanceText))
    }

    private fun notifyEvent(id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, PikminGpsApp.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_flower)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
            .build()
        runCatching { nm.notify(id, n) }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            }
            v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Throwable) {
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Like [openApp], plus the extra that makes MainActivity ask 回到虛擬位置？; its own request code keeps that extra off [openApp]. */
    private fun confirmBackToVirtual(): PendingIntent = PendingIntent.getActivity(
        ctx, REQUEST_CONFIRM_VIRTUAL,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONFIRM_SWITCH, MainActivity.CONFIRM_VIRTUAL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Like [confirmBackToVirtual], the other way round (the Maps question's notification). */
    private fun confirmToReal(): PendingIntent = PendingIntent.getActivity(
        ctx, REQUEST_CONFIRM_REAL,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONFIRM_SWITCH, MainActivity.CONFIRM_REAL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun service(action: String): PendingIntent = PendingIntent.getForegroundService(
        ctx, action.hashCode(),
        Intent(ctx, PatrolService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ID_ONGOING = 1
        const val ID_ARRIVED = 2
        const val ID_HOME = 3
        const val ID_ERROR = 4
        const val ID_MOCK_LOST = 5
        const val ID_LEAK = 6
        const val ID_MAPS_ASK = 7

        /** openApp() uses 0; the same request code would let FLAG_UPDATE_CURRENT copy the confirm extra onto it. */
        private const val REQUEST_CONFIRM_VIRTUAL = 1
        private const val REQUEST_CONFIRM_REAL = 2

        fun formatDistance(m: Double): String =
            if (m >= 1000) "%.2f km".format(m / 1000) else "%.0f m".format(m)
    }
}
