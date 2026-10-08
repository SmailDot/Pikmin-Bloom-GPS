package app.pikminbloom.gps.service

import app.pikminbloom.gps.data.PatrolPhase

/** Whether this tick re-pushes the position and whether the CPU must stay awake for the next one. */
data class PowerPlan(val push: Boolean, val wakeLock: Boolean)

/**
 * While the mock is on, every phase pushes every tick (1 s) and holds the CPU, whether the avatar moves or not and
 * whether the screen is on or off. Only 真實位置, where no mock exists, lets the CPU go.
 *
 * History: 1.3.0 (T15) let PAUSED / PARKED with the screen off push nothing and sleep; on 1.3.2 the game's day map
 * showed the real position (Taiwan) between flowers in Japan. The first 1.4.0 build still re-pushed those only every
 * 5 s. Open-source spoofers that fight the same jump all push at least every second (900 ms, 500 ms: "faster
 * prevents jump back to real GPS", PLAN O5), and the user's call was to drop the power saving rather than risk
 * strikes for jumping back and forth (2026-10-08). Battery is the price.
 */
object StationaryPower {
    fun plan(phase: PatrolPhase, @Suppress("UNUSED_PARAMETER") interactive: Boolean): PowerPlan = when (phase) {
        PatrolPhase.SUSPENDED -> PowerPlan(push = false, wakeLock = false)
        else -> PowerPlan(push = true, wakeLock = true)
    }

    /** The longest step the simulator takes in one tick, whatever the clock says. */
    private const val MAX_TICK_S = 3.0

    /**
     * Seconds the simulator advances this tick: the time since the last one, at most [MAX_TICK_S].
     * A tick that starts without the wakelock may be the first one after the CPU slept (a 繼續 or a move
     * command can be handled before it), so it walks nothing rather than a clamped step. Moving phases hold
     * the lock, so they only lose a tick to this when a 12 h timeout has just lapsed.
     */
    fun tickSeconds(nowMs: Long, lastTickMs: Long, wakeLockHeld: Boolean): Double =
        if (wakeLockHeld) ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, MAX_TICK_S) else 0.0
}
