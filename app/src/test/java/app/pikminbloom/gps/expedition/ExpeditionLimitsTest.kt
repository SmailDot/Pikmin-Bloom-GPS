package app.pikminbloom.gps.expedition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The 最多派幾次 field: blank is the default, 1..50 is accepted, everything else is rejected. */
class ExpeditionLimitsTest {

    @Test
    fun `an empty field means the default of 10`() {
        assertEquals(10, ExpeditionLimits.parseMax(""))
    }

    @Test
    fun `a field of only spaces also means the default of 10`() {
        assertEquals(10, ExpeditionLimits.parseMax("   "))
    }

    @Test
    fun `1 is the smallest accepted limit`() {
        assertEquals(1, ExpeditionLimits.parseMax("1"))
    }

    @Test
    fun `50 is the largest accepted limit`() {
        assertEquals(50, ExpeditionLimits.parseMax("50"))
    }

    @Test
    fun `surrounding spaces around a number are ignored`() {
        assertEquals(7, ExpeditionLimits.parseMax(" 7 "))
    }

    @Test
    fun `0 is rejected`() {
        assertNull(ExpeditionLimits.parseMax("0"))
    }

    @Test
    fun `51 is rejected`() {
        assertNull(ExpeditionLimits.parseMax("51"))
    }

    @Test
    fun `a negative number is rejected`() {
        assertNull(ExpeditionLimits.parseMax("-1"))
    }

    @Test
    fun `a decimal is rejected`() {
        assertNull(ExpeditionLimits.parseMax("5.0"))
    }

    @Test
    fun `a number with a unit word is rejected`() {
        assertNull(ExpeditionLimits.parseMax("10 次"))
    }

    @Test
    fun `a Chinese numeral is rejected`() {
        assertNull(ExpeditionLimits.parseMax("十"))
    }

    @Test
    fun `an emoji is rejected`() {
        assertNull(ExpeditionLimits.parseMax("😀"))
    }

    @Test
    fun `a number too large for an Int is rejected instead of overflowing`() {
        assertNull(ExpeditionLimits.parseMax("99999999999999999999"))
    }
}
