package app.pikminbloom.gps.service

/**
 * Every re-post of the ongoing notification is a round trip through system_server plus a SystemUI
 * re-inflate. Post only when the text changed, and not more often than [minGapMs] unless the caller
 * forced it (lastPostMs = 0, set on phase changes).
 */
object NotificationGate {
    fun shouldPost(nowMs: Long, lastPostMs: Long, lastText: String?, text: String, minGapMs: Long): Boolean =
        text != lastText && (lastPostMs == 0L || nowMs - lastPostMs >= minGapMs)
}
