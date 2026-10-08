package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.service.MockGuard
import app.pikminbloom.gps.service.MockHealthLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The real position reached the game while the avatar was in Japan (2026-10-08): what counts as a leak. */
class MockGuardTest {
    private val tokyo = LatLng(35.6812, 139.7671)
    private val taipei = LatLng(25.0330, 121.5654)

    @Test
    fun aRealFixFarFromTheAvatarIsALeak() {
        assertTrue(MockGuard.isLeak(fixIsMock = false, phase = PatrolPhase.PAUSED, fix = taipei, virtual = tokyo))
    }

    @Test
    fun ourOwnMockFixesAreNever() {
        assertFalse(MockGuard.isLeak(fixIsMock = true, phase = PatrolPhase.PAUSED, fix = taipei, virtual = tokyo))
    }

    @Test
    fun aRealFixNextToTheAvatarIsHarmless() {
        // The user may really be standing near the virtual position: the game sees no jump.
        val near = GeoMath.offsetMeters(tokyo, 60.0, 0.0)
        assertFalse(MockGuard.isLeak(fixIsMock = false, phase = PatrolPhase.WALKING, fix = near, virtual = tokyo))
    }

    @Test
    fun aLateFixOfOursIsNotALeakEvenWhenTheAvatarHasMovedOn() {
        // At 1200 km/h the avatar is ~330 m further each second; a fix pushed two ticks ago comes back unmarked.
        val pushedEarlier = GeoMath.offsetMeters(tokyo, -660.0, 0.0)
        assertFalse(MockGuard.isLeak(fixIsMock = false, phase = PatrolPhase.WALKING, fix = pushedEarlier, virtual = tokyo, recentPushes = listOf(pushedEarlier, tokyo)))
        assertTrue("...but the real position still is", MockGuard.isLeak(fixIsMock = false, phase = PatrolPhase.WALKING, fix = taipei, virtual = tokyo, recentPushes = listOf(pushedEarlier, tokyo)))
    }

    @Test
    fun onlyPhasesThatHoldTheMockAreGuarded() {
        val guarded = setOf(PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.PAUSED, PatrolPhase.RETURNING_HOME, PatrolPhase.PARKED, PatrolPhase.MANUAL)
        for (p in PatrolPhase.entries) {
            assertEquals("$p", p in guarded, MockGuard.guarding(p))
            // 真實位置 shows the real GPS on purpose; starting / stopping have no mock (yet / any more).
            assertEquals("$p leak", p in guarded, MockGuard.isLeak(fixIsMock = false, phase = p, fix = taipei, virtual = tokyo))
        }
    }

    @Test
    fun aLeakLeavesThePatrolOnTheRealPositionInsteadOfPullingItBack() {
        // "反覆外洩又立刻回去…更容易被偵測出定位異常": one stay on the real position, one jump back on the user's tap.
        for (p in PatrolPhase.entries.filter { MockGuard.guarding(it) }) {
            assertEquals("$p", PatrolPhase.SUSPENDED, MockGuard.afterLeak(p))
            assertTrue("$p must be able to go to 真實位置", app.pikminbloom.gps.service.RealMode.canEnter(p))
        }
    }

    @Test
    fun noVirtualPositionNoLeak() {
        assertFalse(MockGuard.isLeak(fixIsMock = false, phase = PatrolPhase.WALKING, fix = taipei, virtual = null))
    }

    @Test
    fun dueAfterTheIntervalOrWhenNeverDone() {
        assertTrue(MockGuard.due(nowMs = 5_000L, lastMs = null, everyMs = 10_000L))
        assertFalse(MockGuard.due(nowMs = 15_000L, lastMs = 6_000L, everyMs = 10_000L))
        assertTrue(MockGuard.due(nowMs = 16_000L, lastMs = 6_000L, everyMs = 10_000L))
    }

    @Test
    fun logLinesCarryTheLocalTimeOnOneLine() {
        // 2026-10-07 18:12:45 UTC = 10-08 03:12:45 in Tokyo.
        val ms = java.time.Instant.parse("2026-10-07T18:12:45Z").toEpochMilli()
        assertEquals("10-08 03:12:45 LEAK a b", MockHealthLog.line(ms, ZoneId.of("Asia/Tokyo"), "LEAK a\nb"))
    }

    @Test
    fun theLogKeepsItsNewerHalfWhenItGrows() {
        val lines = (1..2_000).joinToString("\n", postfix = "\n") { "line $it" }
        val kept = MockHealthLog.trimmed(lines, maxBytes = 4_000)
        assertTrue("shorter", kept.length <= 2_000)
        assertTrue("starts on a whole line", kept.startsWith("line "))
        assertTrue("keeps the newest", kept.endsWith("line 2000\n"))
        assertEquals("small text is left alone", "a\nb\n", MockHealthLog.trimmed("a\nb\n", maxBytes = 4_000))
    }
}
