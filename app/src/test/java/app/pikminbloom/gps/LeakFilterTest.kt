package app.pikminbloom.gps

import app.pikminbloom.gps.mock.LeakFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the passive leak watch passes on to MockGuard (review findings, 2026-10-08). */
class LeakFilterTest {
    private val s = 1_000_000_000_000L          // when the mock was started (elapsed realtime, ns)
    private val ours = listOf("gps", "network", "fused")
    private val grace = LeakFilter.FLP_GRACE_NANOS

    private fun platform(provider: String, at: Long, isMock: Boolean = false) =
        LeakFilter.passes(isMock, at, s, viaFlp = false, flpMockSinceNanos = 0L, provider = provider, ourProviders = ours)

    private fun flp(at: Long, since: Long, isMock: Boolean = false) =
        LeakFilter.passes(isMock, at, s, viaFlp = true, flpMockSinceNanos = since, provider = "Play services FLP", ourProviders = ours)

    @Test
    fun aRealFixThroughOneOfOurProvidersPasses() {
        // Our test provider is gone: the real GPS is back for everyone.
        assertTrue(platform("gps", s + 5))
    }

    @Test
    fun mockFixesAndFixesFromBeforeTheStartNeverPass() {
        assertFalse("ours", platform("gps", s + 5, isMock = true))
        assertFalse("the start's own GPS fix, still in flight", platform("gps", s - 1))
        assertFalse("via FLP before the start", flp(at = s - 1, since = s))
    }

    @Test
    fun aProviderWeDoNotMockIsNoLeak() {
        // 設定 turned 模擬 network off: its real fixes are expected, and re-installing ours would not stop them.
        val gpsOnly = listOf("gps")
        assertFalse(LeakFilter.passes(false, s + 5, s, viaFlp = false, flpMockSinceNanos = 0L, provider = "network", ourProviders = gpsOnly))
    }

    @Test
    fun playServicesIsOnlyWatchedOnceItsMockModeIsConfirmedAndSettled() {
        // 回到虛擬位置: for the half second before setMockMode(true) answers, Play services still serves Taiwan.
        assertFalse("not confirmed yet", flp(at = s + 500_000_000L, since = 0L))
        val on = s + 400_000_000L
        assertFalse("inside the grace period", flp(at = on + grace - 1, since = on))
        assertTrue("after it", flp(at = on + grace, since = on))
    }
}
