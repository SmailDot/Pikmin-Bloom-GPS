package app.pikminbloom.gps.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The robot button's tap rule: a run in progress is cancelled; otherwise the service must be on and Android 11+. */
class AutoButtonTest {

    @Test
    fun aRunInProgressIsCancelledWhateverTheServiceAndAndroidAre() {
        assertEquals(AutoButtonAction.CANCEL, AutoButton.decide(busy = true, accessibilityOn = false, sdk = 28))
        assertEquals(AutoButtonAction.CANCEL, AutoButton.decide(busy = true, accessibilityOn = true, sdk = 30))
    }

    @Test
    fun withTheServiceOffTheButtonExplains() {
        assertEquals(AutoButtonAction.EXPLAIN, AutoButton.decide(busy = false, accessibilityOn = false, sdk = 36))
    }

    @Test
    fun belowAndroid11TheButtonExplainsEvenWithTheServiceOn() {
        assertEquals(AutoButtonAction.EXPLAIN, AutoButton.decide(busy = false, accessibilityOn = true, sdk = 29))
    }

    @Test
    fun onAndroid11WithTheServiceOnTheButtonOffersTheChoices() {
        assertEquals(AutoButtonAction.CHOOSE, AutoButton.decide(busy = false, accessibilityOn = true, sdk = 30))
        assertEquals(AutoButtonAction.CHOOSE, AutoButton.decide(busy = false, accessibilityOn = true, sdk = 36))
    }

    @Test
    fun theExplanationOffersOpeningSettingsOnlyFromAndroid11() {
        assertTrue(AutoButton.canOpenSettings(30))
        assertFalse(AutoButton.canOpenSettings(29))
    }
}
