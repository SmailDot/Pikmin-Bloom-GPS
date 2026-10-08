package app.pikminbloom.gps

import app.pikminbloom.gps.ui.JoystickLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The joystick was stuck bottom-left and too big (2026-10-08): it now moves and has three sizes. */
class JoystickLayoutTest {
    @Test
    fun startsSmallerThanTheOld150dpPad() {
        assertTrue(JoystickLayout.DEFAULT_SIZE_DP < 150)
    }

    @Test
    fun theSizeButtonCyclesSmallMediumLarge() {
        val (s, m, l) = JoystickLayout.SIZES_DP
        assertEquals(m, JoystickLayout.nextSize(s))
        assertEquals(l, JoystickLayout.nextSize(m))
        assertEquals(s, JoystickLayout.nextSize(l))
        assertEquals("unknown starts over", s, JoystickLayout.nextSize(150))
        assertEquals("小", JoystickLayout.label(s)); assertEquals("大", JoystickLayout.label(l))
    }

    @Test
    fun anOldOrOddStoredSizeFallsBackToTheDefault() {
        assertEquals(JoystickLayout.DEFAULT_SIZE_DP, JoystickLayout.validSize(150))
        assertEquals(140, JoystickLayout.validSize(140))
    }

    @Test
    fun draggingNeverPutsThePadOffScreen() {
        assertEquals(0 to 0, JoystickLayout.clamp(-50, -10, 300, 1080, 2400))
        assertEquals(780 to 2100, JoystickLayout.clamp(5000, 9000, 300, 1080, 2400))
        assertEquals(100 to 200, JoystickLayout.clamp(100, 200, 300, 1080, 2400))
    }
}
