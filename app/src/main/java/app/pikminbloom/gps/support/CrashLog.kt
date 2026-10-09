package app.pikminbloom.gps.support

import android.content.Context
import android.util.Log
import app.pikminbloom.gps.BuildConfig
import java.io.File

/**
 * The app's last crash, kept so it can go into a bug report (2026-10-09: "把 Error 發給我"). Only the app's own uncaught
 * exceptions, written to a private file the moment one happens; nothing leaves the phone until the user sends a report
 * (FeedbackDialog), and the next start offers that once.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"
    private const val PREFS = "crash_log"
    private const val KEY_OFFERED_AT = "offered_at"

    /** Plenty for a stack trace with its causes, small enough to write while the process is going down. */
    const val MAX_CHARS = 16_000

    /** A crash older than this goes into no report any more: whatever it was, the user has moved on. */
    const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L

    data class Crash(val atMs: Long, val version: String, val trace: String)

    /** Writes the crash, then hands it on to the handler that was there before (Android's "app has stopped"). */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = format(System.currentTimeMillis(), BuildConfig.VERSION_NAME, thread.name, Log.getStackTraceString(error))
                File(app.filesDir, FILE_NAME).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The newest crash of the last [KEEP_MS], or null. */
    fun latest(context: Context, nowMs: Long): Crash? {
        val file = File(context.filesDir, FILE_NAME)
        val crash = runCatching { parse(file.readText()) }.getOrNull() ?: return null
        return crash.takeIf { nowMs - it.atMs in 0..KEEP_MS }
    }

    /** The newest crash that has not been offered for a report yet. */
    fun unoffered(context: Context, nowMs: Long): Crash? {
        val offeredAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_OFFERED_AT, 0L)
        return latest(context, nowMs)?.takeIf { it.atMs > offeredAt }
    }

    fun markOffered(context: Context, crash: Crash) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_OFFERED_AT, crash.atMs).apply()
    }

    /** "at=…\nversion=…\nthread=…\n\n<stack trace>": what [install] writes and [parse] reads. */
    fun format(atMs: Long, version: String, thread: String, trace: String): String =
        "at=$atMs\nversion=$version\nthread=$thread\n\n" + trace.take(MAX_CHARS)

    fun parse(text: String): Crash? {
        val head = text.substringBefore("\n\n", missingDelimiterValue = "")
        val fields = head.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val atMs = fields["at"]?.toLongOrNull() ?: return null
        val trace = text.substringAfter("\n\n").trim()
        if (trace.isEmpty()) return null
        return Crash(atMs, fields["version"].orEmpty(), trace)
    }
}
