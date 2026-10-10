package app.pikminbloom.gps.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.widget.Toast
import app.pikminbloom.gps.BuildConfig
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.support.AutoRunLog
import app.pikminbloom.gps.support.CrashLog
import app.pikminbloom.gps.support.FeedbackInfo
import app.pikminbloom.gps.support.FeedbackKind
import app.pikminbloom.gps.support.FeedbackReport
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 回報問題／建議 (2026-10-09): pick a bug or a suggestion, then email or GitHub - both, so a player without a GitHub
 * account can still write. The report opens filled in (FeedbackReport) in the mail app or the browser, where the user
 * reads and edits it before anything is sent; with neither installed it is copied to the clipboard instead.
 */
object FeedbackDialog {

    fun show(activity: Activity, kind: FeedbackKind = FeedbackKind.BUG) {
        var picked = kind
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dlg_feedback_title)
            .setSingleChoiceItems(
                arrayOf(activity.getString(R.string.feedback_kind_bug), activity.getString(R.string.feedback_kind_idea)),
                picked.ordinal,
            ) { _, which -> picked = FeedbackKind.entries[which] }
            .setPositiveButton(R.string.feedback_via_email) { _, _ -> sendByEmail(activity, picked) }
            .setNeutralButton(R.string.feedback_via_github) { _, _ -> openGithub(activity, picked) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun sendByEmail(activity: Activity, kind: FeedbackKind) {
        val info = collect(activity, kind)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(FeedbackReport.mailtoUri(kind, info)))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(FeedbackReport.EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, FeedbackReport.subject(kind, BuildConfig.VERSION_NAME))
            .putExtra(Intent.EXTRA_TEXT, FeedbackReport.body(kind, info))
        launchOrCopy(activity, intent, FeedbackReport.body(kind, info), FeedbackReport.EMAIL)
    }

    private fun openGithub(activity: Activity, kind: FeedbackKind) {
        val info = collect(activity, kind)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FeedbackReport.githubUrl(kind, info)))
        launchOrCopy(activity, intent, FeedbackReport.body(kind, info), FeedbackReport.NEW_ISSUE_URL)
    }

    private fun launchOrCopy(activity: Activity, intent: Intent, body: String, where: String) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Pikmin Bloom GPS", body))
            Toast.makeText(activity, activity.getString(R.string.toast_feedback_copied, where), Toast.LENGTH_LONG).show()
        }
    }

    private fun collect(context: Context, kind: FeedbackKind): FeedbackInfo {
        val state = PatrolService.state.value
        val bug = kind == FeedbackKind.BUG
        return FeedbackInfo(
            app = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            // The phone's own language, not the app's (Android 13+ can set the app's apart).
            language = "app ${Lang.current.name.lowercase()} / phone ${Resources.getSystem().configuration.locales[0].toLanguageTag()}",
            patrol = state.phase.takeIf { bug && it != PatrolPhase.IDLE }?.name,
            lastError = state.lastError.takeIf { bug },
            lastExit = if (bug) lastExit(context) else null,
            crash = if (bug) CrashLog.latest(context, System.currentTimeMillis()) else null,
            autoRunLog = if (bug) AutoRunLog.readLastRun(context) else null,
        )
    }

    /** Why the app's process last ended (Android 11+): a crash, the system reclaiming memory, an update… */
    private fun lastExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val info = runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else -> "REASON_${info.reason}"
        }
        val at = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(info.timestamp))
        return listOfNotNull(reason, at, info.description?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }
}
