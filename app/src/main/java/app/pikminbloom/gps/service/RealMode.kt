package app.pikminbloom.gps.service

import app.pikminbloom.gps.data.PatrolPhase

/**
 * Rules of 真實位置 (PatrolPhase.SUSPENDED). Android has no per-app mock location, so while it lasts
 * the game sees the real position too; the virtual state waits, untouched.
 */
object RealMode {
    private val ENTERABLE = setOf(
        PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.PAUSED,
        PatrolPhase.PARKED, PatrolPhase.MANUAL, PatrolPhase.RETURNING_HOME,
    )

    /** Only a phase with a virtual position to keep can switch to the real one. */
    fun canEnter(phase: PatrolPhase): Boolean = phase in ENTERABLE

    /**
     * Phase after 回到虛擬位置: the one it left, except that a pad still up takes over a moving phase
     * (as PatrolService.onJoystickToggled does) and a pad put away meanwhile hands a MANUAL walk back
     * to the route (WALKING; the caller re-plans from where the avatar is).
     */
    fun phaseOnLeave(before: PatrolPhase?, joystickEnabled: Boolean): PatrolPhase = when {
        before == null -> PatrolPhase.WALKING
        joystickEnabled && before != PatrolPhase.PAUSED -> PatrolPhase.MANUAL
        !joystickEnabled && before == PatrolPhase.MANUAL -> PatrolPhase.WALKING
        else -> before
    }

    /**
     * A 繼續 arriving on the real position (NectarRunner's, after the run it paused the patrol for) is
     * kept for 回到虛擬位置, but only for a patrol that left paused; a 暫停 arriving after it drops it.
     */
    fun resumeCarriesOver(before: PatrolPhase?, resume: Boolean): Boolean = resume && before == PatrolPhase.PAUSED
}
