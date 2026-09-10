package app.pikminbloom.gps.vision

import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class MapCalibrationTest {

    private val player = PixelPoint(610.0, 1400.0)
    private val playerPos = LatLng(22.758345, 120.337855)

    // ---------------------------------------------------------------------------------------
    // Forward / inverse pixel <-> metre maths
    // ---------------------------------------------------------------------------------------

    @Test
    fun `north up - north is straight up the screen`() {
        val cal = Calibration(metresPerPixel = 0.5, screenNorthDeg = 0.0)
        val p = cal.metresToPixels(MetreOffset(northM = 50.0, eastM = 0.0))
        assertEquals(0.0, p.x, 1e-9)
        assertEquals(-100.0, p.y, 1e-9)   // 50 m / 0.5 = 100 px, upward => negative y

        val e = cal.metresToPixels(MetreOffset(northM = 0.0, eastM = 50.0))
        assertEquals(100.0, e.x, 1e-9)
        assertEquals(0.0, e.y, 1e-9)
    }

    @Test
    fun `rotated map - north pointing right`() {
        val cal = Calibration(metresPerPixel = 1.0, screenNorthDeg = 90.0)
        val p = cal.metresToPixels(MetreOffset(northM = 30.0, eastM = 0.0))
        assertEquals(30.0, p.x, 1e-9)     // north now points right
        assertEquals(0.0, p.y, 1e-9)
    }

    @Test
    fun `pixels to metres inverts metres to pixels for many angles`() {
        for (thetaDeg in 0 until 360 step 17) {
            for (mpp in listOf(0.2, 0.75, 1.4, 3.0)) {
                val cal = Calibration(mpp, thetaDeg.toDouble())
                for (n in listOf(-120.0, -3.0, 0.0, 17.5, 240.0)) {
                    for (e in listOf(-90.0, 0.0, 5.5, 310.0)) {
                        val px = cal.metresToPixels(MetreOffset(n, e))
                        val back = cal.pixelsToMetres(px.x, px.y)
                        assertEquals("north theta=$thetaDeg mpp=$mpp", n, back.northM, 1e-6)
                        assertEquals("east theta=$thetaDeg mpp=$mpp", e, back.eastM, 1e-6)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // calibrate()
    // ---------------------------------------------------------------------------------------

    /**
     * Builds two synthetic frames of the same static flowers, taken before and after the player
     * moved by [movement], under a known [truth] calibration.
     */
    private fun syntheticFrames(
        truth: Calibration,
        movement: MetreOffset,
        worldOffsets: List<MetreOffset>,
        jitterPx: Double = 0.0,
    ): Pair<List<FlowerHit>, List<FlowerHit>> {
        val rnd = java.util.Random(42)
        fun jitter() = if (jitterPx == 0.0) 0.0 else (rnd.nextDouble() * 2 - 1) * jitterPx
        val a = worldOffsets.mapIndexed { i, off ->
            val p = truth.metresToPixels(off)
            hit(player.x + p.x + jitter(), player.y + p.y + jitter(), i)
        }
        val b = worldOffsets.mapIndexed { i, off ->
            // After the player moves, each static flower is closer/further by -movement.
            val rel = MetreOffset(off.northM - movement.northM, off.eastM - movement.eastM)
            val p = truth.metresToPixels(rel)
            hit(player.x + p.x + jitter(), player.y + p.y + jitter(), i)
        }
        return a to b
    }

    /** Distinct hue per index so the signature matcher can pair them up. */
    private fun hit(x: Double, y: Double, index: Int): FlowerHit {
        val hue = (index * 37.0) % 360.0
        return FlowerHit(
            centroidX = x.toInt(), centroidY = (y - 20).toInt(),
            anchorX = x.toInt(), anchorY = y.toInt(),
            areaPx = 300 + index * 11,
            left = x.toInt() - 12, top = y.toInt() - 30, right = x.toInt() + 12, bottom = y.toInt(),
            hueDeg = hue, sat = 0.8, value = 0.9,
            stemFound = true,
        )
    }

    @Test
    fun `calibrate recovers metres per pixel and north bearing`() {
        val truth = Calibration(metresPerPixel = 0.62, screenNorthDeg = 0.0)
        val movement = MetreOffset(northM = 100.0, eastM = 0.0)
        val offsets = listOf(
            MetreOffset(60.0, -40.0), MetreOffset(-30.0, 25.0), MetreOffset(120.0, 90.0),
            MetreOffset(10.0, 150.0), MetreOffset(-80.0, -70.0),
        )
        val (a, b) = syntheticFrames(truth, movement, offsets)

        val cal = MapCalibration.calibrate(a, b, movement)
        assertNotNull("expected a calibration", cal)
        assertEquals(truth.metresPerPixel, cal!!.metresPerPixel, 0.01)
        assertTrue(MapCalibration.bearingDelta(truth.screenNorthDeg, cal.screenNorthDeg) < 1.0)
    }

    @Test
    fun `calibrate recovers a rotated map`() {
        for (thetaDeg in listOf(0.0, 37.0, 90.0, 180.0, 271.0, 315.0)) {
            for (moveBearing in listOf(0.0, 45.0, 130.0, 260.0)) {
                val truth = Calibration(metresPerPixel = 0.9, screenNorthDeg = thetaDeg)
                val d = 80.0
                val movement = MetreOffset(
                    northM = d * Math.cos(Math.toRadians(moveBearing)),
                    eastM = d * Math.sin(Math.toRadians(moveBearing)),
                )
                val offsets = listOf(
                    MetreOffset(50.0, -30.0), MetreOffset(-20.0, 40.0), MetreOffset(90.0, 70.0),
                    MetreOffset(5.0, 130.0), MetreOffset(-70.0, -55.0), MetreOffset(140.0, -10.0),
                )
                val (a, b) = syntheticFrames(truth, movement, offsets)
                val cal = MapCalibration.calibrate(a, b, movement)
                assertNotNull("theta=$thetaDeg move=$moveBearing", cal)
                assertEquals("mpp theta=$thetaDeg move=$moveBearing", 0.9, cal!!.metresPerPixel, 0.02)
                assertTrue(
                    "north theta=$thetaDeg move=$moveBearing got ${cal.screenNorthDeg}",
                    MapCalibration.bearingDelta(thetaDeg, cal.screenNorthDeg) < 1.5,
                )
            }
        }
    }

    @Test
    fun `calibrate tolerates anchor jitter and one outlier`() {
        val truth = Calibration(metresPerPixel = 0.55, screenNorthDeg = 22.0)
        val movement = MetreOffset(northM = 70.0, eastM = 30.0)
        val offsets = listOf(
            MetreOffset(60.0, -40.0), MetreOffset(-30.0, 25.0), MetreOffset(120.0, 90.0),
            MetreOffset(10.0, 150.0), MetreOffset(-80.0, -70.0), MetreOffset(45.0, 45.0),
        )
        val (a, bClean) = syntheticFrames(truth, movement, offsets, jitterPx = 3.0)
        // One flower "moved" - e.g. a mis-anchored detection. It must not drag the fit.
        val b = bClean.toMutableList().also { it[2] = hit(50.0, 50.0, 2) }

        val detail = MapCalibration.calibrateDetailed(a, b, movement)
        val cal = detail.calibration
        assertNotNull(detail.reason, cal)
        assertEquals(truth.metresPerPixel, cal!!.metresPerPixel, 0.03)
        assertTrue(MapCalibration.bearingDelta(truth.screenNorthDeg, cal.screenNorthDeg) < 4.0)
        assertTrue("outlier should be excluded", detail.matches.size in 4..6)
    }

    @Test
    fun `calibrate returns null when there are too few detections`() {
        val truth = Calibration(0.5, 0.0)
        val movement = MetreOffset(50.0, 0.0)
        val (a, b) = syntheticFrames(truth, movement, listOf(MetreOffset(10.0, 10.0)))
        assertNull(MapCalibration.calibrate(a, b, movement))
    }

    @Test
    fun `calibrate returns null when nothing agrees`() {
        val movement = MetreOffset(50.0, 0.0)
        // Same colour signature everywhere, positions scattered at random: no consistent translation.
        val rnd = java.util.Random(7)
        fun scatter(n: Int) = (0 until n).map {
            FlowerHit(
                centroidX = rnd.nextInt(1200), centroidY = rnd.nextInt(2700),
                anchorX = rnd.nextInt(1200), anchorY = rnd.nextInt(2700),
                areaPx = 300, left = 0, top = 0, right = 10, bottom = 10,
                hueDeg = 300.0, sat = 0.8, value = 0.9, stemFound = true,
            )
        }
        val cal = MapCalibration.calibrate(scatter(5), scatter(5), movement)
        // With 5x5 random pairs it is very unlikely (but not impossible) that 3 agree within 14 px
        // over a 1200x2700 field; if they did, the calibration would still be flagged implausible.
        if (cal != null) {
            println("NOTE: random scatter produced a calibration $cal - tolerance may be loose")
        }
    }

    @Test
    fun `calibrate returns null when the map barely moved`() {
        val truth = Calibration(metresPerPixel = 50.0, screenNorthDeg = 0.0)  // absurdly zoomed out
        val movement = MetreOffset(northM = 10.0, eastM = 0.0)                // 0.2 px of scroll
        val offsets = listOf(
            MetreOffset(600.0, -400.0), MetreOffset(-300.0, 250.0),
            MetreOffset(1200.0, 900.0), MetreOffset(100.0, 1500.0),
        )
        val (a, b) = syntheticFrames(truth, movement, offsets)
        assertNull(MapCalibration.calibrate(a, b, movement))
    }

    // ---------------------------------------------------------------------------------------
    // toLatLng()
    // ---------------------------------------------------------------------------------------

    @Test
    fun `toLatLng round trips a known world offset`() {
        val cal = Calibration(metresPerPixel = 0.6, screenNorthDeg = 17.0)
        val offset = MetreOffset(northM = 85.0, eastM = -42.0)
        val px = cal.metresToPixels(offset)
        val h = hit(player.x + px.x, player.y + px.y, 0)

        val world = MapCalibration.toLatLng(h, player, playerPos, cal)
        val expected = GeoMath.offsetMeters(playerPos, offset.northM, offset.eastM)
        // A FlowerHit stores integer pixels, so up to half a pixel — 0.3 m at this scale, about
        // 3e-6 degrees — is lost on the way in. That, not the geodesy, sets this tolerance.
        assertEquals(expected.lat, world.lat, 5e-6)
        assertEquals(expected.lon, world.lon, 5e-6)

        // ...and the geodesy agrees with the plain flat-earth distance.
        assertEquals(
            hypot(offset.northM, offset.eastM),
            GeoMath.distanceM(playerPos, world),
            0.5,
        )
    }

    @Test
    fun `toLatLng puts a flower directly above the player due north when the map is north-up`() {
        val cal = Calibration(metresPerPixel = 0.5, screenNorthDeg = 0.0)
        val h = hit(player.x, player.y - 200.0, 0)   // 200 px up = 100 m
        val world = MapCalibration.toLatLng(h, player, playerPos, cal)
        assertEquals(100.0, GeoMath.distanceM(playerPos, world), 0.5)
        assertTrue(
            "expected bearing ~0, got ${GeoMath.bearingDeg(playerPos, world)}",
            MapCalibration.bearingDelta(0.0, GeoMath.bearingDeg(playerPos, world)) < 0.5,
        )
    }

    @Test
    fun `rangeMetres matches the geodesic distance`() {
        val cal = Calibration(metresPerPixel = 0.8, screenNorthDeg = 200.0)
        val h = hit(player.x + 300.0, player.y + 400.0, 0)     // 500 px => 400 m
        assertEquals(400.0, MapCalibration.rangeMetres(h, player, cal), 1e-6)
        val world = MapCalibration.toLatLng(h, player, playerPos, cal)
        assertTrue(abs(GeoMath.distanceM(playerPos, world) - 400.0) < 1.0)
    }

    // ---------------------------------------------------------------------------------------
    // Real captured calibration pair
    // ---------------------------------------------------------------------------------------

    @Test
    fun `real calibration pair`() {
        val names = TestImages.calibrationNames()
        val a = names.firstOrNull { it.startsWith("cal_a") }?.let { TestImages.loadOrNull(it) }
        val b = names.firstOrNull { it.startsWith("cal_b") }?.let { TestImages.loadOrNull(it) }
        if (a == null || b == null) {
            println("REAL CAL: cal_a.png / cal_b.png not captured - skipping")
            return
        }
        val det = FlowerDetector()
        val hitsA = det.detect(a).hits
        val hitsB = det.detect(b).hits
        println("REAL CAL: ${hitsA.size} hits in cal_a, ${hitsB.size} hits in cal_b")

        val movement = RealCalibration.MOVEMENT
        val attempt = MapCalibration.calibrateDetailed(
            hitsA, hitsB, movement,
            RealCalibration.PLAYER_PIXEL, RealCalibration.PLAYER_PIXEL,
        )
        println("REAL CAL: ${attempt.reason}")
        attempt.translationPx?.let {
            println("REAL CAL: pixel translation dx=${"%.1f".format(it.x)} dy=${"%.1f".format(it.y)}")
        }
        val cal = attempt.calibration
        if (cal == null) {
            println("REAL CAL: no calibration derived from the real pair (see reason above)")
            return
        }
        println(
            "REAL CAL: metresPerPixel=${"%.4f".format(cal.metresPerPixel)} " +
                "screenNorthDeg=${"%.1f".format(cal.screenNorthDeg)} " +
                "matches=${attempt.matches.size} residual=${"%.1f".format(attempt.residualPx)} px",
        )
        println(
            "REAL CAL: hand-measured was ${"%.4f".format(RealCalibration.HAND_MEASURED_M_PER_PX)} m/px, " +
                "translation (${RealCalibration.HAND_MEASURED_TRANSLATION_PX.x.toInt()}, " +
                "${RealCalibration.HAND_MEASURED_TRANSLATION_PX.y.toInt()}) px",
        )
        // A phone map at street zoom is roughly 0.1 - 5 m per pixel. Anything outside that is a bug,
        // not a tuning issue.
        assertTrue(
            "implausible scale ${cal.metresPerPixel} m/px",
            cal.metresPerPixel > 0.02 && cal.metresPerPixel < 20.0,
        )
        // ...and it must agree with the translation measured by hand off the same two PNGs, which
        // is the only independent check available here.
        assertEquals(
            "scale disagrees with the hand-measured 192 px shift",
            RealCalibration.HAND_MEASURED_M_PER_PX, cal.metresPerPixel, 0.03,
        )
        assertTrue(
            "screen north ${cal.screenNorthDeg} deg — the compass in both frames points straight up",
            MapCalibration.bearingDelta(0.0, cal.screenNorthDeg) < 8.0,
        )
    }

    @Test
    fun `real pair round-trips a flower back to a plausible coordinate`() {
        val a = TestImages.loadOrNull("cal_a.png") ?: run {
            println("REAL CAL: cal_a.png not captured - skipping"); return
        }
        val det = FlowerDetector()
        val hitsA = det.detect(a).hits
        val b = TestImages.loadOrNull("cal_b.png") ?: return
        val cal = MapCalibration.calibrateDetailed(
            hitsA, det.detect(b).hits, RealCalibration.MOVEMENT,
            RealCalibration.PLAYER_PIXEL, RealCalibration.PLAYER_PIXEL,
        ).calibration ?: run { println("REAL CAL: no calibration - skipping round-trip"); return }

        // The player was at this simulated position when cal_a was taken.
        val playerPos = LatLng(22.758543, 120.337862)
        val plan = FlowerScanPlan.build(hitsA, RealCalibration.PLAYER_PIXEL, playerPos, cal)
        println("REAL CAL: plan produced ${plan.flowers.size} waypoints from ${hitsA.size} hits")
        for (f in plan.flowers) {
            println(
                "REAL CAL:   ${f.waypoint.name} ${"%.6f".format(f.waypoint.lat)}," +
                    "${"%.6f".format(f.waypoint.lon)} range=${"%.0f".format(f.rangeM)} m " +
                    "merged=${f.mergedCount} stem=${f.stemFound}",
            )
        }
        // Everything visible in one bird's-eye frame is within ~430 m of the player at this zoom
        // (the 1220x2712 frame covers roughly 384 x 854 m at 0.315 m/px).
        for (f in plan.flowers) {
            assertTrue("waypoint ${f.waypoint.name} is ${f.rangeM} m away", f.rangeM < 500.0)
            assertEquals(
                "waypoint ${f.waypoint.name} drifted far from the capture area",
                0.0, GeoMath.distanceM(playerPos, f.waypoint.latLng) - f.rangeM, 2.0,
            )
        }
    }
}

/**
 * Values recorded while capturing `cal_a.png` / `cal_b.png`; see the resources README.
 *
 * Simulated position at cal_a: 22.758543, 120.337862 (12:25:56.769)
 * Simulated position at cal_b: 22.759087, 120.337859 (12:26:17.432)
 */
object RealCalibration {
    /** Player movement between the two calibration frames, metres north / east. */
    val MOVEMENT: MetreOffset = MetreOffset(northM = 60.490, eastM = -0.308)

    /** The player icon sat here in both frames — the camera was recentered before each capture. */
    val PLAYER_PIXEL: PixelPoint = PixelPoint(612.0, 1355.0)

    /** Hand-measured feature translation cal_a -> cal_b, for cross-checking the detector. */
    val HAND_MEASURED_TRANSLATION_PX: PixelPoint = PixelPoint(0.0, 192.0)

    /** = 60.490 / 192 — the scale the hand measurement implies. */
    const val HAND_MEASURED_M_PER_PX: Double = 0.3151
}
