package app.pikminbloom.gps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration as AndroidConfiguration
import android.util.Log
import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.support.CrashLog
import com.google.android.material.color.DynamicColors
import org.osmdroid.config.Configuration

class PikminGpsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Lang.refresh(this)
        DynamicColors.applyToActivitiesIfAvailable(this)

        // osmdroid needs a user agent for the OSM tile servers and a private cache dir.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = getExternalFilesDir(null) ?: filesDir
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles")
        }

        createNotificationChannels()
        // A new process mid-patrol means the old one was killed: the mock-health diary (MockGuard) wants to know when.
        app.pikminbloom.gps.service.MockHealthLog.append(this, "APP PROCESS START${if (app.pikminbloom.gps.service.PatrolCheckpoint.resumable(this) != null) " (a patrol checkpoint is waiting: the last one was killed)" else ""}")
        cleanupStaleMockProviders()
    }

    /**
     * The phone's language (or, on Android 13+, this app's own language) changed: the Kotlin-built texts follow, and
     * the notification channels are renamed (creating an existing channel again only updates its name and description).
     */
    override fun onConfigurationChanged(newConfig: AndroidConfiguration) {
        super.onConfigurationChanged(newConfig)
        Lang.refresh(this)
        createNotificationChannels()
    }

    /**
     * If the previous process died mid-patrol, its test providers are still installed and the phone's
     * GPS is frozen at the last fake position for every app. Remove them as early as possible.
     */
    private fun cleanupStaleMockProviders() {
        if (PatrolService.isRunning) return
        // A checkpoint means the last patrol died mid-way and the game is still parked at its last
        // mocked position. Leave the providers alone so it STAYS parked; MainActivity offers to
        // resume from that exact spot. Cleaning up here would be the teleport we are avoiding.
        if (app.pikminbloom.gps.service.PatrolCheckpoint.resumable(this) != null) {
            Log.i("PikminGPS", "checkpoint present: keeping stale mock providers for a resume")
            return
        }
        try {
            val mock = MockLocationController(this)
            if (mock.isMockAppSelected()) mock.stop()
        } catch (t: Throwable) {
            Log.w("PikminGPS", "stale mock cleanup failed", t)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PATROL, getString(R.string.channel_patrol_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_patrol_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, getString(R.string.channel_events_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.channel_events_desc)
                enableVibration(true)
            }
        )
        // The screen-capture foreground service (bird's-eye Big Flower scan) needs its own quiet channel.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SCREEN_SCAN, getString(R.string.scan_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.scan_channel_desc)
                setShowBadge(false)
            }
        )
        // 自動探險 runs with no foreground service: its stop button lives in this quiet notification.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EXPEDITION, getString(R.string.expedition_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_PATROL = "patrol"
        const val CHANNEL_EVENTS = "events"
        const val CHANNEL_SCREEN_SCAN = "screen_scan"
        const val CHANNEL_EXPEDITION = "expedition"
    }
}
