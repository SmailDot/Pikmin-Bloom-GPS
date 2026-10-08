package app.pikminbloom.gps.service

import app.pikminbloom.gps.data.PatrolPhase

/** An activity of [packageName] came to the front at [timeMs] (UsageEvents ACTIVITY_RESUMED). */
data class AppEvent(val packageName: String, val timeMs: Long)

/**
 * 開 Google 地圖時詢問要不要切到真實位置 (Settings, off by default): who is in front, and when to react. Until 2026-10-08
 * it switched by itself, so even opening Maps to read a shop's reviews made the game see one jump (and another on the
 * way back): "如果打開Google地圖只是想看某間店的評論就跳轉一次也不大合理". Now it only asks (ui/MapsPrompt).
 */
object ForegroundWatch {
    const val GOOGLE_MAPS = "com.google.android.apps.maps"

    /** The app in front now: the latest resume in [events], else whoever was in front before. */
    fun foreground(events: List<AppEvent>, previous: String?): String? =
        events.maxByOrNull { it.timeMs }?.packageName ?: previous

    /**
     * Only the switch INTO Google Maps asks, once per visit: a 不用 is not asked again while Maps stays in front, and a
     * 回到虛擬位置 tapped inside Maps sticks. Leaving Maps never resumes: going back is a jump and stays the user's tap.
     */
    fun shouldAskForReal(previous: String?, current: String?, phase: PatrolPhase): Boolean =
        current == GOOGLE_MAPS && previous != GOOGLE_MAPS && RealMode.canEnter(phase)

    /** Maps went to the back: a question still up about it is moot. */
    fun leftMaps(previous: String?, current: String?): Boolean = previous == GOOGLE_MAPS && current != GOOGLE_MAPS
}
