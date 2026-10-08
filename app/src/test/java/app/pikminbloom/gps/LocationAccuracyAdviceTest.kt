package app.pikminbloom.gps

import app.pikminbloom.gps.ui.LocationAccuracyAdvice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「Google 定位準確度」 on means a real position ready to leak through any gap in the mock (PLAN O6). */
class LocationAccuracyAdviceTest {
    @Test
    fun aFreshStartWithItOnIsWarned() {
        assertTrue(LocationAccuracyAdvice.shouldWarn(networkProviderEnabled = true, resuming = false, muted = false))
    }

    @Test
    fun offMutedOrResumingIsNot() {
        assertFalse("already off", LocationAccuracyAdvice.shouldWarn(networkProviderEnabled = false, resuming = false, muted = false))
        assertFalse("不再提醒", LocationAccuracyAdvice.shouldWarn(networkProviderEnabled = true, resuming = false, muted = true))
        // A resume keeps the last process's test providers installed: the network provider then reads as on whatever the setting.
        assertFalse("resuming", LocationAccuracyAdvice.shouldWarn(networkProviderEnabled = true, resuming = true, muted = false))
    }
}
