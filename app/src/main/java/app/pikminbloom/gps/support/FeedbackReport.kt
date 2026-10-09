package app.pikminbloom.gps.support

import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.i18n.tr
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class FeedbackKind { BUG, IDEA }

/** What a report says about the phone and the app; FeedbackDialog fills it in. Never a position. */
data class FeedbackInfo(
    /** "1.5.0 (7, release)" */
    val app: String,
    /** "14 (API 34)" */
    val android: String,
    /** "Xiaomi 23078PND5G" */
    val device: String,
    /** "app en / phone ja-JP" */
    val language: String,
    /** The patrol phase, e.g. "WALKING"; null when none runs. */
    val patrol: String? = null,
    /** PatrolState.lastError */
    val lastError: String? = null,
    /** Why the app's process last ended (Android 11+), e.g. "LOW_MEMORY · 2026-10-08 23:10". */
    val lastExit: String? = null,
    val crash: CrashLog.Crash? = null,
)

/**
 * A bug report or a suggestion, ready for an email or a new GitHub issue (2026-10-09: "如果有 BUG 要回報的話可以如何
 * 聯繫我…把 Error 發給我 或是 建議直接傳過來"). The user sees and can edit all of it before sending. Coordinates are
 * masked wherever they might sneak in (an error message, a stack trace): the user cared enough about the location
 * to scrub it from the repository. Pure, so it is unit-tested; in the app's three languages.
 */
object FeedbackReport {
    const val EMAIL = "smaildot@aidot.me"
    const val NEW_ISSUE_URL = "https://github.com/SmailDot/Pikmin-Bloom-GPS/issues/new"

    /** GitHub turns away very long new-issue links; the stack trace is shortened until the link fits. */
    const val MAX_URL_CHARS = 6_000

    /** Stack trace lines in a report; the first ones (the error and where it was thrown) carry the information. */
    const val MAX_TRACE_LINES = 30

    private val COORDINATE = Regex("""-?\d{1,3}\.\d{4,}""")

    /** Masks anything that looks like a latitude or longitude (four or more decimals). */
    fun redact(text: String): String = COORDINATE.replace(text, "#.####")

    fun subject(kind: FeedbackKind, version: String, lang: Lang = Lang.current): String = when (kind) {
        FeedbackKind.BUG -> "[Bug] Pikmin Bloom GPS $version"
        FeedbackKind.IDEA -> tr("[建議]", "[Suggestion]", "[提案]", lang) + " Pikmin Bloom GPS $version"
    }

    fun body(
        kind: FeedbackKind,
        info: FeedbackInfo,
        lang: Lang = Lang.current,
        maxTraceLines: Int = MAX_TRACE_LINES,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        if (kind == FeedbackKind.BUG) {
            appendLine(tr("請描述發生了什麼事、怎麼重現（按了什麼、在哪個畫面）：", "What happened, and how can it be repeated (what you tapped, on which screen)?",
                "何が起きたか、どうすれば再現できるか（何を押したか、どの画面か）を書いてください：", lang))
        } else {
            appendLine(tr("你的建議：", "Your suggestion:", "ご提案：", lang))
        }
        appendLine()
        appendLine()
        appendLine("---")
        appendLine(tr("以下由 App 自動填入，不含位置座標；送出前可以修改或刪除。", "Filled in by the app, without any location; edit or delete freely before sending.",
            "以下はアプリが自動で記入したもので、位置情報は含みません。送信前に編集・削除できます。", lang))
        appendLine("App: ${info.app}")
        appendLine("Android: ${info.android}")
        appendLine(tr("手機", "Phone", "端末", lang) + ": ${info.device}")
        appendLine(tr("語言", "Language", "言語", lang) + ": ${info.language}")
        if (kind == FeedbackKind.IDEA) return@buildString
        info.patrol?.let { appendLine(tr("巡邏狀態", "Patrol", "巡回の状態", lang) + ": $it") }
        info.lastError?.takeIf { it.isNotBlank() }?.let { appendLine(tr("最後的錯誤訊息", "Last error", "最後のエラー", lang) + ": ${redact(it)}") }
        info.lastExit?.let { appendLine(tr("上次 App 結束原因", "How the app last ended", "前回の終了理由", lang) + ": ${redact(it)}") }
        info.crash?.let { crash ->
            val at = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(crash.atMs))
            appendLine()
            appendLine(tr("閃退紀錄", "Crash", "クラッシュ記録", lang) + " ($at, ${crash.version}):")
            val lines = redact(crash.trace).lines()
            lines.take(maxTraceLines).forEach { appendLine(it) }
            if (lines.size > maxTraceLines) appendLine("… (+${lines.size - maxTraceLines})")
        }
    }.trimEnd() + "\n"

    /** A new-issue link with the title and body filled in, the stack trace shortened until it fits [MAX_URL_CHARS]. */
    fun githubUrl(kind: FeedbackKind, info: FeedbackInfo, lang: Lang = Lang.current, zone: ZoneId = ZoneId.systemDefault()): String {
        val title = encode(subject(kind, info.app.substringBefore(' '), lang) + ": ")
        var lines = MAX_TRACE_LINES
        while (true) {
            val url = "$NEW_ISSUE_URL?title=$title&body=" + encode(body(kind, info, lang, lines, zone))
            if (url.length <= MAX_URL_CHARS || lines == 0) return url.take(MAX_URL_CHARS)
            lines = if (lines > 5) lines - 5 else 0
        }
    }

    /** mailto: with the subject and body, for email apps that read them from the link (RFC 6068). */
    fun mailtoUri(kind: FeedbackKind, info: FeedbackInfo, lang: Lang = Lang.current, zone: ZoneId = ZoneId.systemDefault()): String =
        "mailto:$EMAIL?subject=" + encode(subject(kind, info.app.substringBefore(' '), lang)) + "&body=" + encode(body(kind, info, lang, zone = zone))

    /** Percent-encoding for a URL query: spaces as %20 (a "+" would stay a "+" in a mailto: body). */
    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
