package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.route.SegmentKind
import app.pikminbloom.gps.service.TeleportRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 瞬移 moves the avatar and changes nothing else (reported 2026-10-08 on 1.3.2). */
class TeleportRulesTest {
    @Test
    fun aPausedPatrolStaysPausedAfterTheJump() {
        // 1.3.2 set off walking from the flower: "我是按暫停的，那我順過去也要保持暫停".
        for (kind in SegmentKind.entries) assertEquals("PAUSED, first leg $kind", PatrolPhase.PAUSED, TeleportRules.phaseAfter(PatrolPhase.PAUSED, kind))
    }

    @Test
    fun aParkedAvatarStaysStillToo() {
        // PARKED means "at a saved home"; at the flower the same stillness is PAUSED, and 繼續 walks the lap from there.
        assertEquals(PatrolPhase.PAUSED, TeleportRules.phaseAfter(PatrolPhase.PARKED, SegmentKind.TRAVEL))
    }

    @Test
    fun aMovingPatrolWalksOnFromTheFlower() {
        for (p in listOf(PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.MANUAL, PatrolPhase.RETURNING_HOME)) {
            assertEquals("$p, travel leg first", PatrolPhase.WALKING, TeleportRules.phaseAfter(p, SegmentKind.TRAVEL))
            assertEquals("$p, orbit first", PatrolPhase.DWELLING, TeleportRules.phaseAfter(p, SegmentKind.ORBIT))
        }
    }

    @Test
    fun theVehicleInForceSurvivesTheArrivalTheJumpCauses() {
        // "如果我是1200km/h的速度順移到那裏，也保持當前的速度/狀態，不需要自動切換成步行".
        for (v in listOf(TravelMode.CAR, TravelMode.HIGHWAY, TravelMode.PLANE)) {
            assertFalse("$v at the flower jumped to", TeleportRules.dropsVehicleOnArrival(v, arrivedId = "a", teleportTargetId = "a"))
        }
    }

    @Test
    fun aRealDriveStillEndsOnFoot() {
        // The 搶蘑菇 design (PLAN G): a vehicle gets you there, the walk starts there - for every arrival but the jump's.
        assertTrue("no jump pending", TeleportRules.dropsVehicleOnArrival(TravelMode.PLANE, arrivedId = "b", teleportTargetId = null))
        assertTrue("arrived somewhere else than the jump aimed at", TeleportRules.dropsVehicleOnArrival(TravelMode.PLANE, arrivedId = "b", teleportTargetId = "a"))
        assertTrue("unknown flower", TeleportRules.dropsVehicleOnArrival(TravelMode.CAR, arrivedId = null, teleportTargetId = "a"))
    }

    @Test
    fun onFootThereIsNothingToDrop() {
        assertFalse("walking", TeleportRules.dropsVehicleOnArrival(null, arrivedId = "b", teleportTargetId = null))
        for (pace in listOf(TravelMode.WALK, TravelMode.BRISK, TravelMode.RUN)) {
            assertFalse("$pace", TeleportRules.dropsVehicleOnArrival(pace, arrivedId = "b", teleportTargetId = null))
        }
    }
}
