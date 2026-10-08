package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.PatrolPhase

/**
 * Which text the 上次巡邏沒有正常結束 dialog (MainActivity.maybeOfferResume) must show, and the rule of what a resume
 * restores that the text has to agree with (shared with PatrolService.handleResumeCheckpoint, like [useRealModeText]).
 */
object ResumeCopy {
    /**
     * True when the patrol died on 真實位置 (Prefs.suspendedAtMs, 0 = not there): the providers were
     * already gone, so the game sees the real GPS, resuming is the jump and 放棄 is not. 真實位置 never
     * writes a checkpoint, so a flag about this checkpoint is never older than it; an older one is a
     * leftover from an earlier session and must not relabel it.
     */
    fun useRealModeText(suspendedAtMs: Long, checkpointSavedAtMs: Long): Boolean =
        suspendedAtMs > 0 && suspendedAtMs >= checkpointSavedAtMs

    /**
     * True when resuming this checkpoint puts the patrol back PAUSED, at the checkpoint position, instead of walking: a patrol
     * left PAUSED for days (often in another country, maybe on a vehicle override) must not walk or fly off the moment the app
     * is back; nothing moves until 繼續 (seen 2026-10-03: 134 m in 27 s on foot). 走回家 is the user's explicit choice to walk,
     * so it wins. Every other phase resumes as it always did (PatrolService.handleResumeCheckpoint):
     * - WALKING / DWELLING / MANUAL: the unvisited flowers are planned from the checkpoint position and walked;
     * - RETURNING_HOME: keeps walking home (to the saved home when that 回家 had one);
     * - PARKED: re-parks in place;
     * - null / unreadable (PatrolCheckpoint.fromJson reads an unknown name as WALKING): the walk.
     * A route with no flowers left walks home whatever this says, as a live patrol does when its last flower is deleted.
     */
    fun restoresPaused(checkpointPhase: PatrolPhase?, thenReturnHome: Boolean): Boolean =
        checkpointPhase == PatrolPhase.PAUSED && !thenReturnHome

    /**
     * True when the dialog must say that 從中斷點繼續 comes back paused: it asks [restoresPaused] for that button, so the text
     * and the service cannot disagree. After a kill on 真實位置 ([useRealModeText]) its text wins: it leads with the jump,
     * and a patrol that was paused before the switch still comes back paused.
     */
    fun usePausedText(checkpointPhase: PatrolPhase?, real: Boolean): Boolean =
        !real && restoresPaused(checkpointPhase, thenReturnHome = false)
}
