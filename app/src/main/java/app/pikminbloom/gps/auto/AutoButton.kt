package app.pikminbloom.gps.auto

/** What the bar's robot button does when tapped. */
enum class AutoButtonAction { CANCEL, EXPLAIN, CHOOSE }

/**
 * The robot button's tap rule, as plain logic so it can be tested without Android. A run in progress is cancelled.
 * Otherwise the accessibility service must be on and Android must be 11 or newer, else the button explains what the
 * feature needs. Only then does it offer the choices.
 */
object AutoButton {
    /** Android 11 (API 30): the first version with AccessibilityService.takeScreenshot. */
    const val MIN_SDK = 30

    fun decide(busy: Boolean, accessibilityOn: Boolean, sdk: Int): AutoButtonAction = when {
        busy -> AutoButtonAction.CANCEL
        !accessibilityOn || sdk < MIN_SDK -> AutoButtonAction.EXPLAIN
        else -> AutoButtonAction.CHOOSE
    }

    /** The explanation offers 「去開啟」 only where the service can work (Android 11+); below that only 「知道了」. */
    fun canOpenSettings(sdk: Int): Boolean = sdk >= MIN_SDK
}
