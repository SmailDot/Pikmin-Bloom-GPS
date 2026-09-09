package app.pikminbloom.gps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.android.material.color.DynamicColors
import org.osmdroid.config.Configuration

class PikminGpsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

        // osmdroid needs a user agent for the OSM tile servers and a private cache dir.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = getExternalFilesDir(null) ?: filesDir
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles")
        }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PATROL, "巡邏狀態", NotificationManager.IMPORTANCE_LOW).apply {
                description = "巡邏進行中的常駐通知"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "抵達提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "抵達巨大花朵、回家完成、錯誤等提醒"
                enableVibration(true)
            }
        )
    }

    companion object {
        const val CHANNEL_PATROL = "patrol"
        const val CHANNEL_EVENTS = "events"
    }
}
