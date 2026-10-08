package app.pikminbloom.gps.service

sealed class PatrolEvent {
    companion object {
        /** SwitchedToReal's reason when the game had already seen the real position (MockGuard): the app only stopped fighting it. */
        const val REASON_LEAK = "leak"
    }

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
    /** 自動拉花 finished for [flowerName]; [ok] = the info page was reached and swiped (not: nectar received). */
    data class Nectar(val flowerName: String, val ok: Boolean, val detail: String) : PatrolEvent()
    /** 真實位置: the mock was removed ([reason]: "user", "Google Maps" or [REASON_LEAK]); the virtual state is kept. */
    data class SwitchedToReal(val reason: String) : PatrolEvent()
    /** 回到虛擬位置: the mock is back at the kept position. */
    data object SwitchedToVirtual : PatrolEvent()
    /** 前往後停在這裡 arrived: the patrol is paused at [name] until 繼續. */
    data class StayedAt(val name: String) : PatrolEvent()

}
