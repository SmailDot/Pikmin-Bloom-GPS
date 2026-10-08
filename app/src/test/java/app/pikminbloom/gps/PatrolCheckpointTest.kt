package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.TravelMode
import java.time.LocalDate
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.service.PatrolCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatrolCheckpointTest {
    private fun cp(returnTarget: LatLng?) = PatrolCheckpoint(
        savedAtMs = 1_000L, startedAtMs = 500L,
        home = LatLng(22.7415, 121.1324), position = LatLng(22.7500, 121.1400),
        routeId = "r", lap = 2, targetWaypointIndex = 1, phase = PatrolPhase.RETURNING_HOME,
        distanceWalkedM = 123.0, stepsAccrued = 10.5, stepsFlushed = 7L, flushWindowStartMs = 900L,
        distanceSinceFlush = 3.0, stepsWrittenToday = 42L, lapsCompleted = 1,
        returnTarget = returnTarget,
        travelOverride = TravelMode.PLANE,
        ledgerDay = LocalDate.of(2026, 9, 13),
        doneIds = listOf("a", "b"),
    )

    @Test
    fun returnTargetSurvivesRoundTrip() {
        val original = cp(LatLng(35.6812, 139.7671))
        assertEquals(original, PatrolCheckpoint.fromJson(original.toJson()))
    }

    @Test
    fun missingReturnTargetDecodesToNull() {
        val original = cp(null)
        val decoded = PatrolCheckpoint.fromJson(original.toJson())
        assertEquals(original, decoded)
        assertNull(decoded!!.returnTarget)
    }

    @Test
    fun vehicleLedgerDayAndVisitedIdsSurviveRoundTrip() {
        val cp = cp(null)
        val back = PatrolCheckpoint.fromJson(cp.toJson())!!
        assertEquals(TravelMode.PLANE, back.travelOverride)
        assertEquals(LocalDate.of(2026, 9, 13), back.ledgerDay)
        assertEquals(listOf("a", "b"), back.doneIds)
        // walking, nothing visited, no day -> the fields are simply absent
        val plain = cp(null).copy(travelOverride = null, ledgerDay = null, doneIds = emptyList())
        assertEquals(plain, PatrolCheckpoint.fromJson(plain.toJson()))
    }

    @Test
    fun oldCheckpointWithoutTheFieldStillLoads() {
        val legacy = """{"savedAtMs":1000,"homeLat":22.7,"homeLon":120.3,"posLat":22.8,"posLon":120.4,"phase":"PARKED"}"""
        val decoded = PatrolCheckpoint.fromJson(legacy)
        assertEquals(PatrolPhase.PARKED, decoded!!.phase)
        assertNull(decoded.returnTarget)
        assertNull(decoded.travelOverride)
        assertNull(decoded.ledgerDay)
        assertEquals(emptyList<String>(), decoded.doneIds)
    }

    @Test
    fun theStayTargetSurvivesAKillOvernight() {
        // 前往後停在這裡 on a long trip at night: a kill on the way must still stop at that flower (2026-10-08).
        val back = PatrolCheckpoint.fromJson(cp(null).copy(stayAtId = "wp-asakusa").toJson())!!
        assertEquals("wp-asakusa", back.stayAtId)
        assertEquals("none armed", null, PatrolCheckpoint.fromJson(cp(null).toJson())!!.stayAtId)
    }
}
