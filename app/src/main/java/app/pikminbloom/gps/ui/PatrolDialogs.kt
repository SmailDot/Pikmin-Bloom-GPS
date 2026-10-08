package app.pikminbloom.gps.ui

import android.content.Context
import android.content.DialogInterface
import android.view.View
import androidx.appcompat.app.AlertDialog
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Home
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.service.PatrolService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The dialogs the main screen and the floating bar share, so both show the same complete text (T18b: the
 * bar's popup menus were cut off at the screen edge at a large font size). Built, not shown: MainActivity
 * shows them as they are, OverlayService as overlay windows. [Context]s need a Material theme (Theme.PikminGps).
 */
object PatrolDialogs {
    /** The confirm / stop buttons and the 回家 rows wake up after this, so a double tap on whatever opened the dialog cannot land on them. */
    private const val CONFIRM_ENABLE_DELAY_MS = 800L

    /**
     * 切到真實位置？／回到虛擬位置？ (T18): the game sees a jump either way, so a tap only ever opens this
     * dialog, which says so; cancelling, back or a tap outside change nothing. Null when the switch no longer
     * applies ([onStale] got the reason). On 確定 the phase is checked again - the dialog may have stayed up,
     * or come from a stale notification - and [onStale] says why instead of sending a switch that no longer applies.
     */
    fun confirmRealSwitch(context: Context, toReal: Boolean, state: PatrolState, onStale: (String) -> Unit): AlertDialog? {
        RealModeCopy.staleSwitchText(toReal, state.phase)?.let { onStale(it); return null }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(RealModeCopy.confirmTitle(toReal))
            .setMessage(RealModeCopy.confirmMessage(toReal, RealModeCopy.jumpMeters(state.home, state.position)))
            .setPositiveButton(RealModeCopy.confirmButton(toReal)) { _, _ ->
                // The phase may have moved on while the dialog was up: say why rather than send a stale switch.
                val stale = RealModeCopy.staleSwitchText(toReal, PatrolService.state.value.phase)
                when {
                    stale != null -> onStale(stale)
                    toReal -> PatrolService.switchToReal(context)
                    else -> PatrolService.switchToVirtual(context)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        armAfterDelay(dialog) { it.getButton(DialogInterface.BUTTON_POSITIVE) }
        return dialog
    }

    /**
     * 開 Google 地圖時詢問 (2026-10-08): [confirmRealSwitch]'s choice, worded for Maps, with 不用 as the easy answer.
     * Null when the switch no longer applies ([onStale] got the reason); checked again on the confirm tap.
     */
    fun confirmRealForMaps(context: Context, state: PatrolState, onStale: (String) -> Unit): AlertDialog? {
        RealModeCopy.staleSwitchText(true, state.phase)?.let { onStale(it); return null }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(RealModeCopy.mapsTitle())
            .setMessage(RealModeCopy.mapsMessage(RealModeCopy.jumpMeters(state.home, state.position)))
            .setPositiveButton(RealModeCopy.confirmButton(toReal = true)) { _, _ ->
                val stale = RealModeCopy.staleSwitchText(true, PatrolService.state.value.phase)
                if (stale != null) onStale(stale) else PatrolService.switchToReal(context)
            }
            .setNegativeButton(RealModeCopy.mapsNo(), null)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        armAfterDelay(dialog) { it.getButton(DialogInterface.BUTTON_POSITIVE) }
        return dialog
    }

    /**
     * 停止 (2026-10-08): always asked, from the floating bar as well as the main screen ([StopConfirm]); the button that
     * stops is dead for a moment like the jump buttons above. [onReturnFirst] opens the 回家 picker (先回家). Null when
     * nothing runs. The stop itself goes through PatrolService.stop, a no-op once the patrol has ended meanwhile.
     */
    fun confirmStop(context: Context, phase: PatrolPhase, onReturnFirst: () -> Unit): AlertDialog? {
        val kind = StopConfirm.forPhase(phase) ?: return null
        val b = MaterialAlertDialogBuilder(context)
        val stopWhich = when (kind) {
            StopConfirm.SUGGEST_HOME -> {
                b.setTitle(R.string.dlg_stop_title).setMessage(R.string.dlg_stop_msg)
                    .setPositiveButton(R.string.action_return_home_first) { _, _ -> onReturnFirst() }
                    .setNegativeButton(R.string.action_stop_anyway) { _, _ -> PatrolService.stop(context) }
                    .setNeutralButton(R.string.action_cancel, null)
                DialogInterface.BUTTON_NEGATIVE
            }
            else -> {
                when (kind) {
                    StopConfirm.PARKED -> b.setTitle(R.string.dlg_stop_parked_title).setMessage(R.string.dlg_stop_parked_msg)
                    StopConfirm.RETURNING -> b.setTitle(R.string.dlg_stop_plain_title).setMessage(R.string.dlg_stop_returning_msg)
                    StopConfirm.REAL -> b.setTitle(R.string.dlg_stop_plain_title).setMessage(R.string.dlg_stop_real_msg)
                    else -> b.setTitle(R.string.dlg_stop_plain_title).setMessage(R.string.dlg_stop_starting_msg)
                }
                b.setPositiveButton(R.string.btn_stop) { _, _ -> PatrolService.stop(context) }
                    .setNegativeButton(R.string.action_cancel, null)
                DialogInterface.BUTTON_POSITIVE
            }
        }
        val dialog = b.create()
        dialog.setCanceledOnTouchOutside(true)
        armAfterDelay(dialog) { it.getButton(stopWhich) }
        return dialog
    }

    /**
     * 回家: every place (real position, then saved homes), each on foot or as a teleport - the user picks per use.
     * Centred, any row can sit under the finger that opened it, and the 瞬移 rows are jumps: dead for a moment too.
     */
    fun returnPicker(context: Context, homes: List<Home>, onPick: (ReturnChoice) -> Unit): AlertDialog {
        val choices = ReturnChoices.build(homes)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dlg_return_pick_title)
            .setItems(choices.map { ReturnChoices.label(context, it) }.toTypedArray()) { _, which -> onPick(choices[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        armAfterDelay(dialog) { it.listView }
        return dialog
    }

    /**
     * [part] of the shown [dialog] (its confirm button, its list) wakes up [CONFIRM_ENABLE_DELAY_MS] later, and only
     * while the dialog still shows: a double tap on whatever opened it must not land on a jump. A disabled list
     * swallows taps without picking a row (AbsListView.onTouchEvent), so the dialog stays up instead of closing
     * on an ignored pick (a list dialog dismisses itself after any item click).
     */
    private fun armAfterDelay(dialog: AlertDialog, part: (AlertDialog) -> View?) {
        dialog.setOnShowListener {
            val v = part(dialog) ?: return@setOnShowListener
            v.isEnabled = false
            v.postDelayed({ if (dialog.isShowing) v.isEnabled = true }, CONFIRM_ENABLE_DELAY_MS)
        }
    }
}
