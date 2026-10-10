package app.pikminbloom.gps

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Settings the user retired on 2026-10-03 ("少個功能，使用者也不會困擾"): deleted from the settings
 * screen and the code, not hidden. Reads the resource files the same way StringFormatTest does.
 */
class RetiredSettingsTest {
    private fun res(path: String): String =
        listOf(File("src/main/res/$path"), File("app/src/main/res/$path")).first { it.exists() }.readText()

    /** SettingsActivity loads both files into one screen, so a retired key has to be absent from each. */
    private val settingsFiles = listOf("xml/preferences.xml", "xml/preferences_extra.xml")

    private fun assertGoneFromSettings(vararg keys: String) {
        for (file in settingsFiles) {
            val xml = res(file)
            for (key in keys) assertFalse("$key is still in $file", xml.contains(key))
        }
    }

    @Test
    fun speedJitterIsGoneFromSettings() {
        assertGoneFromSettings("speed_jitter_pct")
    }

    @Test
    fun accuracyAndAltitudeAreGoneFromSettings() {
        assertGoneFromSettings("accuracy_min_m", "accuracy_max_m", "altitude_m")
    }

    @Test
    fun globalReturnModeIsGoneFromSettings() {
        assertGoneFromSettings("return_mode")
    }

    @Test
    fun perFlowerRadiusAndDwellAreGoneFromTheFlowerDialogAndSettings() {
        assertGoneFromSettings("default_radius_m", "default_dwell_sec")
        val dialog = res("layout/dialog_waypoint.xml")
        for (id in listOf("@+id/etRadius", "@+id/etDwell", "@+id/tilRadius", "@+id/tilDwell")) {
            assertFalse("$id is still in dialog_waypoint.xml", dialog.contains(id))
        }
    }

    @Test
    fun theAutoExpeditionSwitchIsGoneBecauseTheRobotButtonIsAlwaysOnTheBar() {
        // 2026-10-10: nobody found the feature behind the switch; the robot button on the bar is always shown.
        assertGoneFromSettings("auto_expedition", "pref_auto_expedition")
    }

    @Test
    fun theCadenceCeilingIsGoneFromSettings() {
        // 2026-10-08: "步頻我不認為一般人會去動那個，就別寫出設定了".
        assertGoneFromSettings("max_cadence_spm")
    }
}
