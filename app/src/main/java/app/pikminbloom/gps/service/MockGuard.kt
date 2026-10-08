package app.pikminbloom.gps.service

import app.pikminbloom.gps.data.LocationJump
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng

/**
 * Rules for keeping the mock in place (2026-10-08: "我的GPS會瞬間回到台灣(真實位子)再跳回去我虛擬的位置… 可能是我在
 * 睡覺的時候?螢幕關閉的時候?"; the game's day map showed footprints in Taiwan between flowers in Japan).
 *
 * Nothing on the phone told us when or why, so PatrolService now watches for it: every location fix the system
 * hands out goes past a passive listener (MockLocationController), and one that is not ours, far from where the
 * game should see the avatar, is a leak - logged (MockHealthLog) and answered by [afterLeak]. Trouble the game cannot
 * have seen yet (a push that failed, the permission coming back) is mended silently. The mock-location permission is
 * re-checked every [OP_CHECK_MS] (PLAN N6 caught the system revoking it during a long pause).
 */
object MockGuard {
    /**
     * How often the mock-location permission (AppOps) is re-read while guarding: 3 s like lesterppo/pikmin-android-tools,
     * whose notes say toggling Developer options resets it to deny while Settings still shows the app selected (PLAN O5).
     */
    const val OP_CHECK_MS = 3_000L

    /** At most one re-install per this long: a permission that is gone makes every attempt fail. */
    const val REPAIR_MIN_GAP_MS = 5_000L

    /** A tick this late means the engine did not run (process frozen or the CPU asleep): worth a line in the log. */
    const val TICK_GAP_LOG_MS = 10_000L

    /** The same kind of log line at most this often; the ones in between are counted into the next. */
    const val LOG_REPEAT_MS = 60_000L

    /** Phases in which the game must only ever see the mock. Not while starting or stopping, nor on 真實位置 (on purpose). */
    fun guarding(phase: PatrolPhase): Boolean = when (phase) {
        PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.PAUSED, PatrolPhase.RETURNING_HOME,
        PatrolPhase.PARKED, PatrolPhase.MANUAL -> true
        PatrolPhase.IDLE, PatrolPhase.STARTING, PatrolPhase.STOPPING, PatrolPhase.SUSPENDED -> false
    }

    /**
     * A fix the system handed out is a leak when it is not a mock fix, the patrol is guarding, and it is far enough from
     * the virtual position - and from every fix we pushed lately ([recentPushes]: at 1200 km/h the avatar is several
     * hundred metres on by the time a fix of ours comes back) - that the game would have seen a jump. A real fix
     * next to the avatar is harmless (the user is standing near it) and is not reported.
     */
    fun isLeak(fixIsMock: Boolean, phase: PatrolPhase, fix: LatLng, virtual: LatLng?, recentPushes: List<LatLng> = emptyList()): Boolean {
        if (fixIsMock || !guarding(phase) || virtual == null) return false
        if (GeoMath.distanceM(fix, virtual) < LocationJump.MIN_REPORTED_M) return false
        return recentPushes.none { GeoMath.distanceM(fix, it) < LocationJump.MIN_REPORTED_M }
    }

    /**
     * What a leak turns the patrol into: 真實位置 (PatrolPhase.SUSPENDED), and it stays there until the user taps
     * 回到虛擬位置. The game has seen the real position already; re-mocking at once would be a jump the app makes on its
     * own, and with a cause that keeps coming back a Taiwan ⇄ Japan ping-pong (2026-10-08, the user's call: one stay
     * on the real position and one jump back beats many). Every guarded phase can go there (RealMode.canEnter).
     */
    fun afterLeak(phase: PatrolPhase): PatrolPhase = if (RealMode.canEnter(phase)) PatrolPhase.SUSPENDED else phase

    /** [lastMs] null = never. */
    fun due(nowMs: Long, lastMs: Long?, everyMs: Long): Boolean = lastMs == null || nowMs - lastMs >= everyMs
}
