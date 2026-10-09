package app.pikminbloom.gps.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import app.pikminbloom.gps.BuildConfig
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.databinding.ActivitySettingsBinding
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.steps.DailyLedger
import app.pikminbloom.gps.steps.StepInjector
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Hosts [SettingsFragment]; every key here is read back by [Prefs]. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.settingsContainer.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    /** All tunables; numeric values are stored as Strings on purpose. */
    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            // Alert / battery / auto-return options live in their own file and are merged in here.
            addPreferencesFromResource(R.xml.preferences_extra)

            numeric(Prefs.KEY_ALERT_GAP_SEC, decimal = false, unit = R.string.fmt_second, fallback = "60")
            numeric(Prefs.KEY_AUTO_RETURN_LAPS, decimal = false, unit = R.string.fmt_lap, fallback = "0")

            numeric(Prefs.KEY_SPEED_KMH, decimal = true, unit = R.string.fmt_kmh, fallback = "18")
            for ((mode, key) in Prefs.VEHICLE_SPEED_KEYS) {
                numeric(key, decimal = true, unit = R.string.fmt_kmh, fallback = mode.speedKmh.toInt().toString())
                // Prefs.config() clamps silently; refuse here so the summary never shows a number the engine ignores.
                findPreference<EditTextPreference>(key)?.setOnPreferenceChangeListener { _, value ->
                    val v = value?.toString()?.trim()?.toDoubleOrNull()
                    val ok = v != null && v in Prefs.MIN_VEHICLE_KMH..Prefs.MAX_VEHICLE_KMH
                    if (!ok) {
                        Toast.makeText(requireContext(), getString(R.string.toast_vehicle_speed_range, Prefs.MIN_VEHICLE_KMH.toInt(), Prefs.MAX_VEHICLE_KMH.toInt()), Toast.LENGTH_SHORT).show()
                    }
                    ok
                }
            }
            numeric(Prefs.KEY_STRIDE_CM, decimal = false, unit = R.string.fmt_cm, fallback = "70")
            numeric(Prefs.KEY_STAY_ASK_KM, decimal = true, unit = R.string.fmt_km, fallback = "15")
            findPreference<EditTextPreference>(Prefs.KEY_STAY_ASK_KM)?.let { p ->
                p.summaryProvider = Preference.SummaryProvider<EditTextPreference> { e ->
                    val km = e.text?.trim()?.toDoubleOrNull() ?: TripChoices.DEFAULT_ASK_KM
                    if (km <= 0.0) getString(R.string.pref_stay_ask_never) else getString(R.string.pref_stay_ask_summary, SpeedStepper.text(km))
                }
            }
            numeric(Prefs.KEY_STEP_FLUSH_SEC, decimal = false, unit = R.string.fmt_second, fallback = "60")
            numeric(Prefs.KEY_DAILY_STEP_CAP, decimal = false, unit = R.string.fmt_step, fallback = "")
            // Empty = no cap (2026-10-08): say so instead of "步", and refuse what Prefs would read as no cap by accident (0, "abc").
            findPreference<EditTextPreference>(Prefs.KEY_DAILY_STEP_CAP)?.let { cap ->
                cap.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    DailyLedger.parseCap(p.text)?.let { getString(R.string.fmt_step, it.toString()) } ?: getString(R.string.pref_daily_cap_unlimited)
                }
                cap.setOnPreferenceChangeListener { _, value ->
                    val ok = DailyLedger.isValidCapInput(value?.toString())
                    if (!ok) Toast.makeText(requireContext(), R.string.toast_daily_cap_invalid, Toast.LENGTH_SHORT).show()
                    ok
                }
            }

            findPreference<SwitchPreferenceCompat>(Prefs.KEY_OVERLAY_ENABLED)
                ?.setOnPreferenceChangeListener { _, value ->
                    onOverlayEnabledChanged(value as? Boolean ?: false)
                    true
                }

            // 自動拉花 is a debug-build experiment: release does not show it at all.
            if (!Prefs.AUTO_NECTAR_AVAILABLE) findPreference<Preference>(Prefs.KEY_AUTO_NECTAR)?.let { it.parent?.removePreference(it) }
            // 自動拉花 needs the accessibility service, which only the user can enable in system settings.
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_AUTO_NECTAR)
                ?.setOnPreferenceChangeListener { _, value ->
                    if (value == true && !app.pikminbloom.gps.nectar.NectarAccessibilityService.isEnabled) {
                        Toast.makeText(requireContext(), R.string.toast_nectar_enable_service, Toast.LENGTH_LONG).show()
                        app.pikminbloom.gps.nectar.NectarAccessibilityService.openSettings(requireContext())
                    }
                    true
                }

            // 9c needs 使用情況存取權, which only the user can grant in system settings.
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_AUTO_REAL_FOR_MAPS)
                ?.setOnPreferenceChangeListener { _, value ->
                    if (value == true && !Permissions.hasUsageAccess(requireContext())) {
                        Toast.makeText(requireContext(), R.string.toast_usage_access_needed, Toast.LENGTH_LONG).show()
                        Permissions.openUsageAccessSettings(requireContext())
                    }
                    true
                }

            findPreference<Preference>(KEY_DELETE_TODAY)?.setOnPreferenceClickListener {
                // The running patrol keeps its own 今日已寫入步數; it must re-read after the delete.
                confirmDeleteTodaySteps(); true
            }
            findPreference<Preference>(KEY_VERSION)?.summary = BuildConfig.VERSION_NAME
            findPreference<Preference>(KEY_FEEDBACK)?.setOnPreferenceClickListener {
                FeedbackDialog.show(requireActivity()); true
            }
            findPreference<Preference>(KEY_DISCLAIMER)?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dlg_disclaimer_title)
                    .setMessage(R.string.dlg_disclaimer_msg)
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
                true
            }
        }

        /**
         * The switch itself always flips; without "顯示在其他應用程式上層" the bar simply never shows,
         * so send the user to the system screen (HyperOS also needs 後台彈出介面).
         */
        private fun onOverlayEnabledChanged(enabled: Boolean) {
            val context = requireContext()
            if (!enabled) {
                OverlayService.stop(context)
                return
            }
            if (!Permissions.canDrawOverlays(context)) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dlg_overlay_permission_title)
                    .setMessage(R.string.dlg_overlay_permission_msg)
                    .setPositiveButton(R.string.action_open_overlay_settings) { _, _ ->
                        if (!Permissions.openOverlaySettings(context)) {
                            Toast.makeText(context, R.string.toast_no_activity, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
                return
            }
            // Already patrolling: show it right away instead of waiting for the next start.
            if (PatrolService.isRunning) OverlayService.start(context)
        }

        /** Numeric [EditTextPreference]: right keyboard + a summary that shows the value with its unit. */
        private fun numeric(key: String, decimal: Boolean, @StringRes unit: Int, fallback: String) {
            val pref = findPreference<EditTextPreference>(key) ?: return
            pref.setOnBindEditTextListener { editText ->
                editText.inputType = if (decimal) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else {
                    InputType.TYPE_CLASS_NUMBER
                }
                editText.setSelection(editText.text?.length ?: 0)
            }
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                getString(unit, p.text?.takeIf { it.isNotBlank() } ?: fallback)
            }
        }

        private fun confirmDeleteTodaySteps() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dlg_delete_steps_title)
                .setMessage(R.string.dlg_delete_steps_msg)
                .setPositiveButton(R.string.action_delete) { _, _ -> deleteTodaySteps() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        private fun deleteTodaySteps() {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val ok = StepInjector(context).deleteOurRecordsToday()
                Toast.makeText(
                    context,
                    if (ok) R.string.toast_delete_steps_ok else R.string.toast_delete_steps_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        companion object {
            private const val KEY_DELETE_TODAY = "delete_today_steps"
            private const val KEY_VERSION = "app_version"
            private const val KEY_FEEDBACK = "feedback"
            private const val KEY_DISCLAIMER = "disclaimer"
        }
    }
}
