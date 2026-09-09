package app.pikminbloom.gps.service

sealed class PatrolEvent {
    data class ArrivedAtWaypoint(val index: Int, val name: String) : PatrolEvent()
    data class LapFinished(val lap: Int) : PatrolEvent()
    data object ReturnedHome : PatrolEvent()
    data object Stopped : PatrolEvent()
    data class Error(val message: String) : PatrolEvent()
    data class StepsWritten(val count: Long, val todayTotal: Long) : PatrolEvent()
}
