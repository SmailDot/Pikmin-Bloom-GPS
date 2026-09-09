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
import app.pikminbloom.gps.BuildConfig
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.databinding.ActivitySettingsBinding
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

    /** All tunables from docs/PLAN.md §3.2; numeric values are stored as Strings on purpose. */
    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            numeric(Prefs.KEY_SPEED_KMH, decimal = true, unit = R.string.fmt_kmh, fallback = "4.7")
            numeric(Prefs.KEY_SPEED_JITTER_PCT, decimal = true, unit = R.string.fmt_percent, fallback = "10")
            numeric(Prefs.KEY_STRIDE_CM, decimal = false, unit = R.string.fmt_cm, fallback = "70")
            numeric(Prefs.KEY_DEFAULT_RADIUS, decimal = true, unit = R.string.fmt_meter, fallback = "30")
            numeric(Prefs.KEY_DEFAULT_DWELL, decimal = false, unit = R.string.fmt_second, fallback = "120")
            numeric(Prefs.KEY_STEP_FLUSH_SEC, decimal = false, unit = R.string.fmt_second, fallback = "60")
            numeric(Prefs.KEY_DAILY_STEP_CAP, decimal = false, unit = R.string.fmt_step, fallback = "50000")
            numeric(Prefs.KEY_ACC_MIN, decimal = true, unit = R.string.fmt_meter, fallback = "3")
            numeric(Prefs.KEY_ACC_MAX, decimal = true, unit = R.string.fmt_meter, fallback = "9")
            numeric(Prefs.KEY_ALTITUDE, decimal = true, unit = R.string.fmt_meter, fallback = "20")

            findPreference<Preference>(KEY_DELETE_TODAY)?.setOnPreferenceClickListener {
                confirmDeleteTodaySteps(); true
            }
            findPreference<Preference>(KEY_VERSION)?.summary = BuildConfig.VERSION_NAME
            findPreference<Preference>(KEY_DISCLAIMER)?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dlg_disclaimer_title)
                    .setMessage(R.string.dlg_disclaimer_msg)
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
                true
            }
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
            private const val KEY_DISCLAIMER = "disclaimer"
        }
    }
}
