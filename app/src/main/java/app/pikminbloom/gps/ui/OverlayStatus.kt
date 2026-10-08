package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.PatrolState

/** Pure pieces of the floating bar's one-line status (OverlayService.statusText). */
object OverlayStatus {
    /**
     * The step figure on the bar: 今日已寫入 (PatrolState.stepsWrittenToday), which restarts at local
     * midnight together with the daily cap (DailyLedger). It used to be the session count, which by
     * design never resets - "過日時步數依舊累積" (2026-10-03). Null when steps are not written at all.
     *
     * It counts what has been written to Health Connect, not live walking, so it advances in jumps.
     * PatrolService changes it only (1) when a flush is written successfully - one per step_flush_sec,
     * default 60 s, which Prefs clamps to 20-600 s - (2) when it is re-seeded: patrol start or resume,
     * 寫入步數 switched on mid-patrol, 重設今日寫入的步數 (Health Connect is read again, or the checkpoint
     * restored), and (3) at the local-midnight day change, which is checked every tick. While Health
     * Connect is missing or a write fails, and once the daily cap is reached, it stays frozen even
     * though the patrol keeps walking (PatrolState.sessionSteps still counts).
     */
    fun stepsToday(state: PatrolState, injectSteps: Boolean): Long? = if (injectSteps) state.stepsWrittenToday else null
}
