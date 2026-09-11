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
}
