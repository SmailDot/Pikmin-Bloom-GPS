package app.pikminbloom.gps.service

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.route.SegmentKind

/**
 * A 瞬移過去 (PLAN K) changes where the avatar stands and nothing else (2026-10-08: "譬如我是按暫停的，那我順過去也要保持
 * 暫停；如果我是1200km/h的速度順移到那裏，也保持當前的速度/狀態，不需要自動切換成步行").
 */
object TeleportRules {
    /**
     * The phase right after the jump. A stopped avatar (paused, or parked at a saved home) stays stopped at the flower -
     * PAUSED, since PARKED means "at a saved home" - and 繼續 walks the lap on from there. Anything moving walks on.
     */
    fun phaseAfter(before: PatrolPhase, firstLegKind: SegmentKind): PatrolPhase = when (before) {
        PatrolPhase.PAUSED, PatrolPhase.PARKED -> PatrolPhase.PAUSED
        else -> if (firstLegKind == SegmentKind.ORBIT) PatrolPhase.DWELLING else PatrolPhase.WALKING
    }

    /**
     * Whether arriving at a flower by [vehicle] drops back to walking (PatrolService.onArrived; the 搶蘑菇 design of
     * PLAN G). The jump lands just outside the flower's circle, so the next tick (or the first after 繼續) "arrives"
     * there: that arrival - at [teleportTargetId], the flower jumped to - is the jump's, not a drive's, and keeps
     * whatever vehicle is in force by then (also one picked after the jump, while paused). Every other arrival by
     * vehicle drops to walking as before.
     */
    fun dropsVehicleOnArrival(vehicle: TravelMode?, arrivedId: String?, teleportTargetId: String?): Boolean {
        if (vehicle == null || vehicle.countsSteps) return false
        return arrivedId == null || arrivedId != teleportTargetId
    }
}
