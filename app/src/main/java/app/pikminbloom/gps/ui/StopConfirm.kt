package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.PatrolPhase

/**
 * Which 停止 confirmation a phase gets. The floating bar's square 停止 sat next to 回家 and stopped on the first
 * touch: "很容易誤觸，要有二次確認" (2026-10-08). Now both it and the main screen always ask, and the button that
 * actually stops wakes up only after a moment (PatrolDialogs), so a double tap cannot reach it either.
 */
enum class StopConfirm {
    /** At a saved home: stopping is the one jump back to the real GPS. */
    PARKED,
    /** On the route: suggest 先回家 (a walk) over the jump a plain stop is. */
    SUGGEST_HOME,
    /** Already heading home: stopping now only cuts the walk short (a jump from wherever it got to). */
    RETURNING,
    /** On the real position: nothing jumps, only the virtual patrol ends. */
    REAL,
    /** Still starting: no mock to remove yet. */
    STARTING,
    ;

    companion object {
        /** Null when there is nothing to stop. */
        fun forPhase(phase: PatrolPhase): StopConfirm? = when (phase) {
            PatrolPhase.IDLE, PatrolPhase.STOPPING -> null
            PatrolPhase.PARKED -> PARKED
            PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.PAUSED, PatrolPhase.MANUAL -> SUGGEST_HOME
            PatrolPhase.RETURNING_HOME -> RETURNING
            PatrolPhase.SUSPENDED -> REAL
            PatrolPhase.STARTING -> STARTING
        }
    }
}
