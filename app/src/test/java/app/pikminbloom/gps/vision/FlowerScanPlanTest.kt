package app.pikminbloom.gps.vision

import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowerScanPlanTest {

    private val player = PixelPoint(610.0, 1355.0)
    private val playerPos = LatLng(22.758345, 120.337855)
    private val cal = Calibration(metresPerPixel = 0.3151, screenNorthDeg = 0.0)

    private fun hitAt(dx: Double, dy: Double, index: Int = 0): FlowerHit {
        val x = (player.x + dx).toInt()
        val y = (player.y + dy).toInt()
        return FlowerHit(
            centroidX = x, centroidY = y - 85,
            anchorX = x, anchorY = y,
            areaPx = 1500 + index,
            left = x - 24, top = y - 110, right = x + 24, bottom = y - 60,
            hueDeg = 197.0, sat = 0.37, value = 0.86, stemFound = true,
        )
    }

    @Test
    fun `converts each detection into a waypoint`() {
        val hits = listOf(hitAt(0.0, -200.0), hitAt(300.0, 100.0), hitAt(-250.0, 400.0))
        val plan = FlowerScanPlan.build(hits, player, playerPos, cal)
        assertEquals(3, plan.flowers.size)
        assertEquals(0, plan.droppedTooFar)
        assertEquals(0, plan.mergedAway)
        for (f in plan.flowers) {
            assertEquals(Waypoint.DEFAULT_RADIUS_M, f.waypoint.radiusM, 1e-9)
            assertEquals(Waypoint.DEFAULT_DWELL_SEC, f.waypoint.dwellSec)
            assertTrue("id should be scan-prefixed", f.waypoint.id.startsWith("scan-"))
        }
        // 200 px straight up at 0.3151 m/px is 63 m due north.
        val north = plan.flowers.minByOrNull { it.rangeM }!!
        assertEquals(63.0, north.rangeM, 1.0)
        assertTrue(north.waypoint.lat > playerPos.lat)
    }

    @Test
    fun `dedupes detections within twenty metres`() {
        // Three detections 10 px apart: 3 m on the ground, far inside the 20 m dedupe radius.
        val hits = listOf(hitAt(0.0, -200.0, 0), hitAt(10.0, -200.0, 1), hitAt(0.0, -190.0, 2))
        val plan = FlowerScanPlan.build(hits, player, playerPos, cal)
        assertEquals(1, plan.flowers.size)
        assertEquals(2, plan.mergedAway)
        assertEquals(3, plan.flowers[0].mergedCount)
    }

    @Test
    fun `keeps detections just outside the dedupe radius`() {
        // 25 m apart at 0.3151 m/px is 79 px.
        val hits = listOf(hitAt(0.0, -200.0, 0), hitAt(0.0, -279.0, 1))
        val plan = FlowerScanPlan.build(hits, player, playerPos, cal)
        assertEquals(2, plan.flowers.size)
        assertEquals(0, plan.mergedAway)
        assertEquals(
            25.0,
            GeoMath.distanceM(plan.flowers[0].waypoint.latLng, plan.flowers[1].waypoint.latLng),
            1.5,
        )
    }

    @Test
    fun `drops detections beyond the max radius`() {
        val far = hitAt(0.0, -2000.0)     // 630 m north
        val near = hitAt(0.0, -200.0)     // 63 m north
        val plan = FlowerScanPlan.build(listOf(far, near), player, playerPos, cal)
        assertEquals(1, plan.flowers.size)
        assertEquals(1, plan.droppedTooFar)

        // ...and the cap is configurable.
        val wide = FlowerScanPlan.build(
            listOf(far, near), player, playerPos, cal,
            FlowerScanPlan.Options(maxRadiusM = 1_000.0),
        )
        assertEquals(2, wide.flowers.size)
    }

    @Test
    fun `nearest detection wins a merge`() {
        val near = hitAt(0.0, -100.0, 0)          // 32 m
        val far = hitAt(5.0, -140.0, 1)           // 44 m, 13 m from the first
        val plan = FlowerScanPlan.build(listOf(far, near), player, playerPos, cal)
        assertEquals(1, plan.flowers.size)
        // The surviving coordinate is the near one, which is the more accurate measurement.
        assertEquals(32.0, plan.flowers[0].rangeM, 1.5)
    }

    @Test
    fun `requireStem drops fallback anchors when asked`() {
        val withStem = hitAt(0.0, -200.0, 0)
        val withoutStem = hitAt(300.0, -200.0, 1).copy(stemFound = false)
        val lenient = FlowerScanPlan.build(listOf(withStem, withoutStem), player, playerPos, cal)
        assertEquals(2, lenient.flowers.size)
        assertEquals(0, lenient.droppedNoStem)

        val strict = FlowerScanPlan.build(
            listOf(withStem, withoutStem), player, playerPos, cal,
            FlowerScanPlan.Options(requireStem = true),
        )
        assertEquals(1, strict.flowers.size)
        assertEquals(1, strict.droppedNoStem)
    }

    @Test
    fun `mergeInto leaves existing waypoints untouched and skips duplicates`() {
        val existing = listOf(
            Waypoint("hand-1", "手動花", 22.758345, 120.337855),
        )
        // One detection on top of the existing waypoint, one well away from it.
        val onTop = hitAt(0.0, 0.0, 0)
        val elsewhere = hitAt(0.0, -400.0, 1)   // 126 m north
        val plan = FlowerScanPlan.build(listOf(onTop, elsewhere), player, playerPos, cal)
        val merged = FlowerScanPlan.mergeInto(existing, plan)

        assertEquals(2, merged.size)
        assertEquals("hand-1", merged[0].id)
        assertTrue("the new entry should be a scanned one", merged[1].id.startsWith("scan-"))
        assertTrue(
            "the duplicate of the hand-placed waypoint should not have been added",
            merged.count { GeoMath.distanceM(it.latLng, existing[0].latLng) < 20.0 } == 1,
        )
    }

    @Test
    fun `a rotated map still lands the waypoint in the right direction`() {
        // Map rotated so that north points to the right of the screen.
        val rotated = Calibration(metresPerPixel = 0.3151, screenNorthDeg = 90.0)
        val hit = hitAt(200.0, 0.0)   // 200 px to the RIGHT of the player
        val plan = FlowerScanPlan.build(listOf(hit), player, playerPos, rotated)
        assertEquals(1, plan.flowers.size)
        val bearing = GeoMath.bearingDeg(playerPos, plan.flowers[0].waypoint.latLng)
        assertTrue(
            "with north pointing right, a detection to the right should be due north, got $bearing",
            MapCalibration.bearingDelta(0.0, bearing) < 1.0,
        )
    }

    @Test
    fun `empty input produces an empty plan`() {
        val plan = FlowerScanPlan.build(emptyList(), player, playerPos, cal)
        assertEquals(0, plan.flowers.size)
        assertEquals(0, plan.waypoints.size)
    }
}
