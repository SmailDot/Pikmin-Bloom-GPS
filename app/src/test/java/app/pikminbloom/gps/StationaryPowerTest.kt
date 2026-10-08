package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.service.PowerPlan
import app.pikminbloom.gps.service.StationaryPower
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * While the mock is on, every phase pushes every second and holds the CPU, screen on or off (2026-10-08: the
 * power saving of 1.3.0 is gone - "先不走省電 不然跳來跳去容易被三振出局"). Only 真實位置 lets the CPU sleep.
 * A pure rules object over PatrolPhase, in the style of RealModeTest.
 */
class StationaryPowerTest {
    @Test
    fun aStillAvatarIsPushedEverySecondEvenWithTheScreenOff() {
        for (p in listOf(PatrolPhase.PAUSED, PatrolPhase.PARKED)) {
            for (screenOn in listOf(false, true)) {
                assertEquals("$p, screen ${if (screenOn) "on" else "off"}", PowerPlan(push = true, wakeLock = true), StationaryPower.plan(p, interactive = screenOn))
            }
        }
    }

    @Test
    fun everyMovingPhaseToo() {
        for (p in listOf(PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.MANUAL, PatrolPhase.RETURNING_HOME, PatrolPhase.STARTING, PatrolPhase.STOPPING, PatrolPhase.IDLE)) {
            for (screenOn in listOf(false, true)) {
                assertEquals("$p, screen ${if (screenOn) "on" else "off"}", PowerPlan(push = true, wakeLock = true), StationaryPower.plan(p, interactive = screenOn))
            }
        }
    }

    @Test
    fun onlyTheRealPositionLetsTheCpuSleep() {
        for (screenOn in listOf(false, true)) {
            assertEquals("SUSPENDED, screen ${if (screenOn) "on" else "off"}", PowerPlan(push = false, wakeLock = false), StationaryPower.plan(PatrolPhase.SUSPENDED, interactive = screenOn))
        }
    }

    // The first tick after the CPU may have slept must not walk a step of its own (T14/T15 review, 2026-10-03).

    private val hour = 60 * 60 * 1000L

    @Test
    fun aTickWithTheWakelockHeldWalksTheTimeThatPassed() {
        assertEquals("1 s since the last tick", 1.0, StationaryPower.tickSeconds(nowMs = 11_000L, lastTickMs = 10_000L, wakeLockHeld = true), 1e-9)
        assertEquals("2.5 s since the last tick", 2.5, StationaryPower.tickSeconds(nowMs = 12_500L, lastTickMs = 10_000L, wakeLockHeld = true), 1e-9)
    }

    @Test
    fun aGapOfThreeSecondsOrMoreIsClampedToThree() {
        for (gapMs in listOf(3_000L, 3_001L, 10_000L, 8 * hour)) {
            assertEquals("gap $gapMs ms, wakelock held", 3.0, StationaryPower.tickSeconds(nowMs = 100 * hour + gapMs, lastTickMs = 100 * hour, wakeLockHeld = true), 1e-9)
        }
    }

    @Test
    fun aTickThatStartsWithoutTheWakelockWalksNothing() {
        // The CPU may have slept since the last tick: a 繼續 handled before the first tick back must not get a clamped 3 s step.
        for (gapMs in listOf(0L, 1_000L, 3_000L, 8 * hour)) {
            assertEquals("gap $gapMs ms, no wakelock", 0.0, StationaryPower.tickSeconds(nowMs = 100 * hour + gapMs, lastTickMs = 100 * hour, wakeLockHeld = false), 1e-9)
        }
    }

    @Test
    fun aClockThatRanBackwardsWalksNothing() {
        assertEquals("now is before the last tick", 0.0, StationaryPower.tickSeconds(nowMs = 9_000L, lastTickMs = 10_000L, wakeLockHeld = true), 1e-9)
    }
}
