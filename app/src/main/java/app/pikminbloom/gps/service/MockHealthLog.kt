package app.pikminbloom.gps.service

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A small on-phone diary of everything that can make the game see the real GPS (MockGuard): leaks, repairs, the
 * mock-location permission coming and going, ticks that did not run, screen on/off, process and patrol starts.
 * No screen shows it (2026-10-08: a viewer in Settings was "多此一舉" - the phone is read over USB). It exists because
 * logcat's ring buffer is overwritten within hours, so a leak at night is gone from it by morning; this file is not:
 * `adb shell run-as app.pikminbloom.gps cat files/mock_health.log`.
 *
 * Bounded: past [MAX_BYTES] the older half is dropped. Writes are a few dozen bytes, rare (seconds apart at worst).
 */
object MockHealthLog {
    const val FILE_NAME = "mock_health.log"
    const val MAX_BYTES = 64 * 1024

    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    /** "10-08 03:12:45 LEAK …" in the phone's time zone (the mock moves the time zone too, so the zone is printed by [zoneNote]). */
    fun line(nowMs: Long, zone: ZoneId, text: String): String =
        stamp.format(Instant.ofEpochMilli(nowMs).atZone(zone)) + " " + text.replace('\n', ' ')

    /** Keeps the newer part of [text] once it outgrows [maxBytes]: from the first line start after the cut. */
    fun trimmed(text: String, maxBytes: Int = MAX_BYTES): String {
        if (text.length <= maxBytes) return text
        val cut = text.length - maxBytes / 2
        val nl = text.indexOf('\n', cut)
        return if (nl < 0) "" else text.substring(nl + 1)
    }

    @Synchronized
    fun append(context: Context, text: String) {
        val l = line(System.currentTimeMillis(), ZoneId.systemDefault(), text)
        Log.i("PikminGPS", "health: $l")
        runCatching {
            val f = file(context)
            f.appendText(l + "\n")
            if (f.length() > MAX_BYTES) f.writeText(trimmed(f.readText()))
        }.onFailure { Log.w("PikminGPS", "health log write failed", it) }
    }

    /** The zone the phone is in right now: it follows the mock (Tokyo) and jumps back with a leak (Taipei, PLAN N6). */
    fun zoneNote(): String = "tz=" + ZoneId.systemDefault().id

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
