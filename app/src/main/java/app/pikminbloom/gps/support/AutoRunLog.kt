package app.pikminbloom.gps.support

import android.content.Context
import android.os.Build
import android.util.Log
import app.pikminbloom.gps.BuildConfig
import app.pikminbloom.gps.service.MockHealthLog
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The diagnostic log of the auto runs (自動探險 and 自動餵精華), kept so a bug report can say why a run went the way it
 * did. Each run starts with a header line (what it was, on which phone), then one line for each thing the run decided:
 * the screen kind it saw, the cells on the expedition list, whether GO was active, our own tap points, and how it stopped.
 *
 * It never holds pixels or images, and never a location or a coordinate of the user: only screen-kind decisions and the
 * points we tapped ourselves. It stays in the app's private storage and goes into a report only when the user sends one
 * (FeedbackDialog), where the user can read and edit it first.
 *
 * Keeps the last [KEEP_RUNS] runs and at most [MAX_BYTES]; the older lines go first, as MockHealthLog does.
 */
object AutoRunLog {
    const val FILE_NAME = "auto_run.log"
    const val MAX_BYTES = 64 * 1024
    const val KEEP_RUNS = 3

    /** The report's section for the last run: at most this many UTF-8 bytes. */
    const val REPORT_MAX_BYTES = 6 * 1024

    private const val HEADER = "=== "
    private val runStart = Regex("(?m)^" + Regex.escape(HEADER))
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** "12:03:45.678 tap auto at 295,1031": the time in [zone] to the millisecond, then the text on one line. */
    fun line(nowMs: Long, zone: ZoneId, text: String): String =
        stamp.format(Instant.ofEpochMilli(nowMs).atZone(zone)) + " " + text.replace('\n', ' ')

    /** The first line of a run: what it is and the phone it runs on. [detail] is e.g. "target=FRUIT, max=10". */
    fun header(
        kind: String,
        detail: String,
        app: String,
        android: String,
        sdk: Int,
        width: Int,
        height: Int,
        density: Int,
        locale: String,
    ): String = "$HEADER$kind run, $detail, app $app, Android $android/API $sdk, screen ${width}x$height, density $density, locale $locale"

    /** Keeps the newest [runs] runs (each starts at a header line), then at most [maxBytes] as MockHealthLog keeps them. */
    fun trimmed(text: String, runs: Int = KEEP_RUNS, maxBytes: Int = MAX_BYTES): String {
        val starts = runStart.findAll(text).map { it.range.first }.toList()
        val lastRuns = if (starts.size > runs) text.substring(starts[starts.size - runs]) else text
        return MockHealthLog.trimmed(lastRuns, maxBytes)
    }

    /**
     * The last run, from its header to the end without the final newline. Within [maxBytes] of UTF-8: the header, a note
     * that older lines were cut, then the newest whole lines. Null when no run is in [text].
     */
    fun lastRun(text: String, maxBytes: Int = REPORT_MAX_BYTES): String? {
        val start = runStart.findAll(text).lastOrNull()?.range?.first ?: return null
        val section = text.substring(start).trimEnd('\n')
        if (utf8Bytes(section) <= maxBytes) return section
        val headerEnd = section.indexOf('\n').let { if (it < 0) section.length else it }
        val header = section.substring(0, headerEnd)
        val note = "\n… (older lines cut)"
        val room = maxBytes - utf8Bytes(header) - utf8Bytes(note)
        val newest = takeLastBytes(section.substring(headerEnd), room)
        val firstLine = newest.indexOf('\n')
        return header + note + if (firstLine < 0 || room <= 0) "" else newest.substring(firstLine)
    }

    /** The UTF-8 size of [s]: a Chinese character is three bytes, an ASCII one byte. */
    fun utf8Bytes(s: String): Int = s.fold(0) { sum, c -> sum + utf8Size(c) }

    private fun utf8Size(c: Char): Int = when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        c.isSurrogate() -> 2 // half of a four-byte character: a surrogate pair is four bytes
        else -> 3
    }

    /** The longest end of [s] that fits in [bytes] of UTF-8. */
    private fun takeLastBytes(s: String, bytes: Int): String {
        var used = 0
        var from = s.length
        while (from > 0) {
            val size = utf8Size(s[from - 1])
            if (used + size > bytes) break
            used += size
            from--
        }
        return s.substring(from)
    }

    /** Starts a run: its header, with this phone's details, and the older runs trimmed. */
    @Synchronized
    fun startRun(context: Context, kind: String, detail: String) {
        val app = context.applicationContext
        val metrics = app.resources.displayMetrics
        val text = header(
            kind = kind,
            detail = detail,
            app = BuildConfig.VERSION_NAME,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            density = metrics.densityDpi,
            locale = Locale.getDefault().toLanguageTag(),
        )
        runCatching {
            val f = file(app)
            val old = if (f.exists()) f.readText() else ""
            f.writeText(trimmed(old + text + "\n"))
        }.onFailure { Log.w(TAG, "auto run log: start failed", it) }
    }

    /** Appends [text] to the current run, with the time, and keeps the file within its size. */
    @Synchronized
    fun append(context: Context, text: String) {
        val l = line(System.currentTimeMillis(), ZoneId.systemDefault(), text)
        runCatching {
            val f = file(context)
            f.appendText(l + "\n")
            if (f.length() > MAX_BYTES) f.writeText(trimmed(f.readText()))
        }.onFailure { Log.w(TAG, "auto run log: write failed", it) }
    }

    /** The last run, for a report, or null when none is kept. */
    fun readLastRun(context: Context): String? = runCatching { lastRun(file(context).readText()) }.getOrNull()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private const val TAG = "PikminGPS"
}
