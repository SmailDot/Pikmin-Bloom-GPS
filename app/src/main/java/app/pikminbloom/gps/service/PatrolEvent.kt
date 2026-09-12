package app.pikminbloom.gps.service

sealed class PatrolEvent {
    data class ArrivedAtWaypoint(val index: Int, val name: String) : PatrolEvent()
    data class LapFinished(val lap: Int) : PatrolEvent()
    data object ReturnedHome : PatrolEvent()
    data object Stopped : PatrolEvent()
    data class Error(val message: String) : PatrolEvent()
    /** An interrupted patrol was picked up from its checkpoint; [checkpointAgeMs] is how stale it was. */
    data class Resumed(val checkpointAgeMs: Long) : PatrolEvent()
    data class StepsWritten(val count: Long, val todayTotal: Long) : PatrolEvent()
    /** Reached the custom home; the mock stays on until 停止 (PatrolPhase.PARKED). */
    data object ParkedAtHome : PatrolEvent()
    /** The live vehicle override changed (by the user, or dropped to walking on arrival). */
    data class TravelModeChanged(val mode: app.pikminbloom.gps.data.TravelMode?, val automatic: Boolean) : PatrolEvent()
    /** The route was re-planned while walking (waypoints edited, 立刻前往, joystick put away). */
    data class Replanned(val reason: String) : PatrolEvent()
    /** A settings edit was applied mid-patrol; [speedKmh] is the walking speed now in force. */
    data class ConfigChanged(val speedKmh: Double) : PatrolEvent()
}
