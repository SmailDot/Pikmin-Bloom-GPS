package app.pikminbloom.gps.vision

import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.vision.ScanTracker.Companion.FrameVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The decisions behind the bird's-eye scan (`ScanTracker` and the RGBA byte packing), exercised
 * without Android: synthetic detections at known pixels, a synthetic walk, and the calibration the
 * tracker should derive from them.
 */
class FlowerScannerLogicTest {

    private val width = 1220
    private val height = 2712
    private val player = PixelPoint(610.0, 1353.0)
    private val home = LatLng(22.758345, 120.337855)

    /** The calibration measured offline on the capture set; the "truth" the synthetic world uses. */
    private val truth = Calibration(metresPerPixel = 0.3118, screenNorthDeg = 0.3)

    /** Flowers scattered around the player in ground metres (north, east). */
    private val world = listOf(
        MetreOffset(60.0, -40.0), MetreOffset(35.0, 70.0), MetreOffset(-80.0, 10.0),
        MetreOffset(120.0, 30.0), MetreOffset(-30.0, -90.0),
    )

    private fun hit(x: Double, y: Double, index: Int): FlowerHit {
        val xi = x.toInt()
        val yi = y.toInt()
        return FlowerHit(
            centroidX = xi, centroidY = yi - 85, anchorX = xi, anchorY = yi,
            areaPx = 1400 + index * 60,
            left = xi - 25, top = yi - 110, right = xi + 25, bottom = yi - 60,
            hueDeg = 190.0 + index * 25.0, sat = 0.45, value = 0.85, stemFound = index % 2 == 0,
        )
    }

    /** What the detector would report with the player at [pos], under [truth], recentered. */
    private fun frame(pos: LatLng, cal: Calibration = truth, jitterPx: Double = 0.0): DetectionResult {
        val rnd = java.util.Random(pos.hashCode().toLong())
        fun jitter() = if (jitterPx == 0.0) 0.0 else (rnd.nextDouble() * 2 - 1) * jitterPx
        val hits = world.mapIndexedNotNull { i, off ->
            // A flower's ground position is fixed; its offset from the player shrinks as we walk.
            val moved = ScanTracker.movement(home, pos)
            val rel = MetreOffset(off.northM - moved.northM, off.eastM - moved.eastM)
            val p = cal.metresToPixels(rel)
            val x = player.x + p.x + jitter()
            val y = player.y + p.y + jitter()
            if (x < 0 || y < 0 || x >= width || y >= height) null else hit(x, y, i)
        }
        return DetectionResult(
            hits = hits, candidates = hits.size, rejectedByArea = 0, rejectedByShape = 0,
            rejectedByChrome = 0, rejectedByMushroom = 0, rejectedByAvatar = 0,
            badges = emptyList(), avatars = emptyList(),
        )
    }

    private fun empty() = DetectionResult(emptyList(), 0, 0, 0, 0, 0, 0, emptyList(), emptyList())

    private fun walkView(): DetectionResult {
        val hits = (0 until 70).map { i -> hit(30.0 + (i % 10) * 110.0, 300.0 + (i / 10) * 300.0, i) }
        return DetectionResult(hits, hits.size, 0, 0, 0, 0, 0, emptyList(), emptyList())
    }

    // ---------------------------------------------------------------------------------------
    // Pure helpers
    // ---------------------------------------------------------------------------------------

    @Test
    fun `frame classification - walk view, empty, usable`() {
        assertEquals(FrameVerdict.EMPTY, ScanTracker.classifyFrame(0))
        assertEquals(FrameVerdict.USABLE, ScanTracker.classifyFrame(1))
        assertEquals(FrameVerdict.USABLE, ScanTracker.classifyFrame(ScanTracker.WALK_VIEW_HIT_THRESHOLD))
        assertEquals(FrameVerdict.WALK_VIEW, ScanTracker.classifyFrame(ScanTracker.WALK_VIEW_HIT_THRESHOLD + 1))
        assertEquals(FrameVerdict.WALK_VIEW, ScanTracker.classifyFrame(70))
    }

    @Test
    fun `player pixel - nearest avatar to the recentered spot, else the constant`() {
        val fallback = ScanTracker.playerPixel(emptyList(), width, height)
        assertEquals(610.0, fallback.x, 1e-9)
        assertEquals(height * 0.499, fallback.y, 1e-9)

        fun blob(x: Double, y: Double) = Blob(9000, (x - 55).toInt(), (y - 55).toInt(), (x + 55).toInt(), (y + 55).toInt(), x, y, 0.0, 0.05, 0.95)
        val near = blob(618.0, 1349.0)
        val far = blob(1100.0, 2400.0)
        val picked = ScanTracker.playerPixel(listOf(far, near), width, height)
        assertEquals(618.0, picked.x, 1e-9)
        assertEquals(1349.0, picked.y, 1e-9)

        // A lone pill on the far side of the screen is a friend's, not ours.
        val rejected = ScanTracker.playerPixel(listOf(blob(60.0, 2600.0)), width, height)
        assertEquals(fallback, rejected)
    }

    @Test
    fun `movement - north and east metres from two positions`() {
        val north = GeoMath.offsetMeters(home, 60.0, 0.0)
        val m = ScanTracker.movement(home, north)
        assertEquals(60.0, m.northM, 0.05)
        assertEquals(0.0, m.eastM, 0.05)

        val se = GeoMath.offsetMeters(home, -30.0, 40.0)
        val m2 = ScanTracker.movement(home, se)
        assertEquals(-30.0, m2.northM, 0.05)
        assertEquals(40.0, m2.eastM, 0.05)
        assertEquals(50.0, m2.magnitudeM, 0.05)

        assertEquals(0.0, ScanTracker.movement(home, home).magnitudeM, 1e-9)
    }

    @Test
    fun `calibration attempts - first time, then every 300 m`() {
        assertTrue(ScanTracker.shouldAttemptCalibration(hasCalibration = false, metresSinceCalibration = 0.0))
        assertFalse(ScanTracker.shouldAttemptCalibration(hasCalibration = true, metresSinceCalibration = 120.0))
        assertTrue(ScanTracker.shouldAttemptCalibration(hasCalibration = true, metresSinceCalibration = 300.0))
    }

    @Test
    fun `better calibration - plausible, and a lower residual than the current one`() {
        fun attempt(cal: Calibration?, residual: Double) =
            CalibrationAttempt(cal, emptyList(), PixelPoint(0.0, 0.0), residual, "test")

        val good = attempt(Calibration(0.31, 1.0), 5.0)
        assertTrue(ScanTracker.isBetterCalibration(null, good))
        assertFalse(ScanTracker.isBetterCalibration(null, attempt(null, 0.0)))
        assertFalse(ScanTracker.isBetterCalibration(null, attempt(Calibration(Double.NaN, 0.0), 0.0)))
        assertFalse(ScanTracker.isBetterCalibration(good, attempt(Calibration(0.30, 2.0), 7.0)))
        assertTrue(ScanTracker.isBetterCalibration(good, attempt(Calibration(0.30, 2.0), 3.0)))
    }

    @Test
    fun `saved calibration - agrees within tolerance, rejects a zoom change`() {
        val saved = Calibration(0.3118, 0.3)
        assertTrue(ScanTracker.savedCalibrationAgrees(saved, Calibration(0.33, 5.0)))
        assertTrue(ScanTracker.savedCalibrationAgrees(saved, Calibration(0.29, 359.0)))
        assertFalse("zoomed in", ScanTracker.savedCalibrationAgrees(saved, Calibration(0.15, 0.0)))
        assertFalse("zoomed out", ScanTracker.savedCalibrationAgrees(saved, Calibration(0.62, 0.0)))
        assertFalse("rotated", ScanTracker.savedCalibrationAgrees(saved, Calibration(0.31, 45.0)))

        val now = 1_000_000_000_000L
        assertTrue(ScanTracker.isSavedUsable(saved, now - 60_000L, now))
        assertFalse("too old", ScanTracker.isSavedUsable(saved, now - ScanTracker.SAVED_MAX_AGE_MS - 1, now))
        assertFalse("never saved", ScanTracker.isSavedUsable(saved, 0L, now))
        assertFalse("implausible", ScanTracker.isSavedUsable(Calibration(0.0, 0.0), now, now))
        assertFalse(ScanTracker.isSavedUsable(null, now, now))
    }

    @Test
    fun `merge - repeat sightings refine, new flowers append with stable numbering`() {
        val first = FlowerScanPlan.build(frame(home).hits, player, home, truth, ScanTracker.DEFAULT_OPTIONS)
        val merged1 = ScanTracker.mergeFound(emptyList(), first)
        assertEquals(world.size, merged1.size)
        assertEquals(listOf("掃描花1", "掃描花2", "掃描花3", "掃描花4", "掃描花5"), merged1.map { it.waypoint.name })

        // Walk 20 m north and look again: the same flowers, slightly different ranges, no duplicates.
        val pos2 = GeoMath.offsetMeters(home, 20.0, 0.0)
        val second = FlowerScanPlan.build(frame(pos2).hits, player, pos2, truth, ScanTracker.DEFAULT_OPTIONS)
        val merged2 = ScanTracker.mergeFound(merged1, second)
        assertEquals(world.size, merged2.size)
        assertEquals(merged1.map { it.waypoint.name }, merged2.map { it.waypoint.name })
        for ((a, b) in merged1.zip(merged2)) {
            assertTrue("range only shrinks", b.rangeM <= a.rangeM + 1e-9)
            assertTrue("sightings accumulate", b.mergedCount > a.mergedCount)
            assertTrue(GeoMath.distanceM(a.waypoint.latLng, b.waypoint.latLng) < 3.0)
        }
    }

    // ---------------------------------------------------------------------------------------
    // ScanTracker end to end on a synthetic walk
    // ---------------------------------------------------------------------------------------

    @Test
    fun `tracker takes references on the move and calibrates once frames are 40 m apart`() {
        val tracker = ScanTracker()
        assertTrue("no reference yet", tracker.wantsStationaryFrame(home))
        // A moving frame becomes the first reference by itself: the walker never has to stop.
        val o0 = tracker.onFrame(frame(home), width, height, home)
        assertTrue(tracker.hasReference)
        assertTrue("$o0", o0 is ScanTracker.Outcome.Calibrating)
        assertNull(tracker.calibration)

        // Inside the reference spacing nothing new is taken and nothing calibrates.
        val p15 = GeoMath.offsetMeters(home, 15.0, 0.0)
        assertFalse("15 m is inside the spacing", tracker.wantsStationaryFrame(p15))
        assertTrue(tracker.onFrame(frame(p15), width, height, p15) is ScanTracker.Outcome.Calibrating)
        assertNull(tracker.calibration)

        // At 40 m from the first reference the baseline is long enough: the frame is promoted to
        // a reference and calibrated against the first one - still without anyone pausing.
        val p40 = GeoMath.offsetMeters(home, 40.0, 0.0)
        val o40 = tracker.onFrame(frame(p40), width, height, p40)
        assertTrue("$o40", o40 is ScanTracker.Outcome.Scanning)
        val cal = tracker.calibration!!
        assertEquals(truth.metresPerPixel, cal.metresPerPixel, 0.01)
        assertEquals(truth.screenNorthDeg, cal.screenNorthDeg, 2.0)
        assertEquals(world.size, (o40 as ScanTracker.Outcome.Scanning).found.size)
    }

    @Test
    fun `tracker calibrates from two stationary frames 40 m apart and then finds every flower`() {
        val tracker = ScanTracker()
        var pos = home
        var outcome: ScanTracker.Outcome = tracker.onFrame(frame(pos), width, height, pos, stationary = true)
        assertTrue(outcome is ScanTracker.Outcome.Calibrating)
        assertNull(tracker.calibration)

        // 10 m per frame, due north, walking: frames at 10, 20, 30 m are still calibrating...
        for (step in 1..3) {
            pos = GeoMath.offsetMeters(home, 10.0 * step, 0.0)
            outcome = tracker.onFrame(frame(pos), width, height, pos)
            assertTrue("step $step: $outcome", outcome is ScanTracker.Outcome.Calibrating)
            assertEquals(10.0 * step, (outcome as ScanTracker.Outcome.Calibrating).metresSoFar, 0.5)
        }
        // ...and the stationary frame at 40 m lands the calibration.
        pos = GeoMath.offsetMeters(home, 40.0, 0.0)
        outcome = tracker.onFrame(frame(pos), width, height, pos, stationary = true)
        assertTrue("$outcome", outcome is ScanTracker.Outcome.Scanning)
        val scanning = outcome as ScanTracker.Outcome.Scanning
        assertNotNull(scanning.calibrationChanged)
        assertFalse(scanning.adoptedSaved)
        assertEquals(truth.metresPerPixel, scanning.calibration.metresPerPixel, 0.01)
        assertTrue(MapCalibration.bearingDelta(truth.screenNorthDeg, scanning.calibration.screenNorthDeg) < 2.0)

        // Every synthetic flower is recovered within a few metres of where it really is.
        assertEquals(world.size, scanning.found.size)
        for (off in world) {
            val expected = GeoMath.offsetMeters(home, off.northM, off.eastM)
            val nearest = scanning.found.minOf { GeoMath.distanceM(it.waypoint.latLng, expected) }
            assertTrue("flower $off recovered ${"%.1f".format(nearest)} m off", nearest < 3.0)
        }
    }

    @Test
    fun `tracker reads a moving frame's camera position off the scene, not the lagging simulator`() {
        // Measured live: the game draws the avatar several seconds behind the mock fixes. Model
        // that as the scene being rendered for a position 20 m BEHIND the simulated one.
        val tracker = ScanTracker()
        tracker.onFrame(frame(home), width, height, home, stationary = true)
        val p45 = GeoMath.offsetMeters(home, 45.0, 0.0)
        tracker.onFrame(frame(p45), width, height, p45, stationary = true)
        assertNotNull(tracker.calibration)

        val simPos = GeoMath.offsetMeters(home, 70.0, 0.0)
        val shownPos = GeoMath.offsetMeters(home, 50.0, 0.0)   // what the game actually renders
        val o = tracker.onFrame(frame(shownPos), width, height, simPos) as ScanTracker.Outcome.Scanning
        assertTrue("planned from the scene (${o.planPos}), not the simulator ($simPos)",
            GeoMath.distanceM(o.planPos, shownPos) < 2.0)
        assertTrue(GeoMath.distanceM(o.planPos, simPos) > 15.0)
        // ...so the flowers still land where they are, despite the 20 m lag.
        for (off in world) {
            val expected = GeoMath.offsetMeters(home, off.northM, off.eastM)
            val nearest = o.found.minOf { GeoMath.distanceM(it.waypoint.latLng, expected) }
            assertTrue("flower $off recovered ${"%.1f".format(nearest)} m off", nearest < 3.0)
        }
    }

    @Test
    fun `tracker slides the reference forward when a calibration attempt fails`() {
        val tracker = ScanTracker()
        // Reference frame with flowers, then a frame 45 m on that shares no flower with it (all
        // hues shifted), so matching cannot succeed. Both are taken on the move.
        tracker.onFrame(frame(home), width, height, home)
        val far = GeoMath.offsetMeters(home, 45.0, 0.0)
        val alien = frame(far).let { r -> r.copy(hits = r.hits.map { it.copy(hueDeg = it.hueDeg + 90.0, areaPx = it.areaPx * 3) }) }
        val o1 = tracker.onFrame(alien, width, height, far)
        assertTrue("$o1", o1 is ScanTracker.Outcome.Calibrating)
        assertNull(tracker.calibration)
        // The alien frame joined the references (the store keeps several); the reported baseline is
        // the longest one available, i.e. back to the first frame.
        assertEquals(45.0, (o1 as ScanTracker.Outcome.Calibrating).metresSoFar, 0.5)

        val next = GeoMath.offsetMeters(home, 60.0, 0.0)
        val o2 = tracker.onFrame(frame(next), width, height, next)
        assertTrue("$o2", o2 is ScanTracker.Outcome.Calibrating || o2 is ScanTracker.Outcome.Scanning)
    }

    @Test
    fun `tracker adopts a saved calibration after a short validation walk`() {
        val saved = Calibration(0.3118, 0.3)
        val tracker = ScanTracker(savedCalibration = saved)
        tracker.onFrame(frame(home), width, height, home)
        val p10 = GeoMath.offsetMeters(home, 10.0, 0.0)
        assertFalse("10 m is below the validation baseline", tracker.wantsStationaryFrame(p10))
        val o10 = tracker.onFrame(frame(p10), width, height, p10)
        assertTrue("$o10", o10 is ScanTracker.Outcome.Calibrating)

        // A user pause makes a settled frame; 15 m is enough to *validate* a saved scale even
        // though it is far too short to measure one.
        val p15 = GeoMath.offsetMeters(home, 15.0, 0.0)
        val o15 = tracker.onFrame(frame(p15), width, height, p15, stationary = true)
        assertTrue("$o15", o15 is ScanTracker.Outcome.Scanning)
        val s = o15 as ScanTracker.Outcome.Scanning
        assertTrue(s.adoptedSaved)
        assertEquals(saved, s.calibration)
        assertEquals(world.size, s.found.size)
    }

    @Test
    fun `tracker refuses a saved calibration from a different zoom`() {
        // The world is now rendered at twice the zoom (half the metres per pixel).
        val zoomed = Calibration(0.156, 0.3)
        val tracker = ScanTracker(savedCalibration = Calibration(0.3118, 0.3))
        tracker.onFrame(frame(home, zoomed), width, height, home, stationary = true)
        val p15 = GeoMath.offsetMeters(home, 15.0, 0.0)
        val o15 = tracker.onFrame(frame(p15, zoomed), width, height, p15, stationary = true)
        assertTrue("saved must not be adopted: $o15", o15 is ScanTracker.Outcome.Calibrating)
        assertNull(tracker.calibration)

        // A full 40 m measurement then produces the real scale instead.
        val p55 = GeoMath.offsetMeters(home, 55.0, 0.0)
        val o55 = tracker.onFrame(frame(p55, zoomed), width, height, p55, stationary = true)
        assertTrue("$o55", o55 is ScanTracker.Outcome.Scanning)
        val s = o55 as ScanTracker.Outcome.Scanning
        assertFalse(s.adoptedSaved)
        assertEquals(zoomed.metresPerPixel, s.calibration.metresPerPixel, 0.01)
    }

    @Test
    fun `tracker ignores the walk view and counts empty frames`() {
        val tracker = ScanTracker()
        assertEquals(ScanTracker.Outcome.WrongView, tracker.onFrame(walkView(), width, height, home))
        for (i in 1..ScanTracker.EMPTY_STREAK_LIMIT) {
            val o = tracker.onFrame(empty(), width, height, home)
            assertTrue(o is ScanTracker.Outcome.Empty)
            assertEquals(i, (o as ScanTracker.Outcome.Empty).streak)
            assertEquals(i >= ScanTracker.EMPTY_STREAK_LIMIT, o.exhausted)
        }
        // A usable frame resets the streak.
        tracker.onFrame(frame(home), width, height, home)
        val o = tracker.onFrame(empty(), width, height, home)
        assertEquals(1, (o as ScanTracker.Outcome.Empty).streak)
    }

    @Test
    fun `tracker replaces the calibration only with a lower residual`() {
        val tracker = ScanTracker()
        var pos = home
        tracker.onFrame(frame(pos, jitterPx = 4.0), width, height, pos, stationary = true)
        pos = GeoMath.offsetMeters(home, 45.0, 0.0)
        val first = tracker.onFrame(frame(pos, jitterPx = 4.0), width, height, pos, stationary = true) as ScanTracker.Outcome.Scanning
        val firstResidual = tracker.calibrationAttempt!!.residualPx
        assertNotNull(first.calibrationChanged)

        // Keep walking well past the 300 m re-calibration distance, taking a jitter-free
        // stationary frame whenever the tracker asks: the fresh measurement has a lower residual
        // and takes over.
        var lastChange: CalibrationAttempt? = null
        for (step in 1..40) {
            pos = GeoMath.offsetMeters(home, 45.0 + 10.0 * step, 0.0)
            val o = tracker.onFrame(frame(pos), width, height, pos, stationary = tracker.wantsStationaryFrame(pos))
            if (o is ScanTracker.Outcome.Scanning && o.calibrationChanged != null) lastChange = o.calibrationChanged
        }
        assertNotNull("a re-calibration should have happened", lastChange)
        assertTrue(lastChange!!.residualPx <= firstResidual)
        assertTrue(abs(tracker.calibration!!.metresPerPixel - truth.metresPerPixel) < 0.01)
    }

    // ---------------------------------------------------------------------------------------
    // Landmarks and blank frames (what the live device run taught us)
    // ---------------------------------------------------------------------------------------

    /** A mushroom badge: dark teal pill, ~2800 px, fixed to the map like a flower. */
    private fun badge(x: Double, y: Double) =
        Blob(2850, (x - 40).toInt(), (y - 20).toInt(), (x + 40).toInt(), (y + 20).toInt(), x, y, 152.0, 0.40, 0.50)

    /** Frame with only TWO flowers but three mushroom badges — the live scene at the test site. */
    private fun sparseFrame(pos: LatLng): DetectionResult {
        val moved = ScanTracker.movement(home, pos)
        fun px(off: MetreOffset): PixelPoint {
            val p = truth.metresToPixels(MetreOffset(off.northM - moved.northM, off.eastM - moved.eastM))
            return PixelPoint(player.x + p.x, player.y + p.y)
        }
        val flowers = listOf(MetreOffset(-40.0, -140.0), MetreOffset(-55.0, -160.0))
        val badges = listOf(MetreOffset(-30.0, 45.0), MetreOffset(-35.0, 105.0), MetreOffset(-105.0, 75.0))
        val hits = flowers.mapIndexed { i, off -> px(off).let { hit(it.x, it.y, i) } }
        val badgeBlobs = badges.map { off -> px(off).let { badge(it.x, it.y) } }
        return DetectionResult(hits, hits.size, 0, 0, 0, 0, 0, badges = badgeBlobs, avatars = emptyList())
    }

    @Test
    fun `landmarks - badges join the flowers for calibration, with their own signature`() {
        val r = sparseFrame(home)
        val lm = ScanTracker.calibrationLandmarks(r)
        assertEquals(5, lm.size)
        val badgeLm = lm.drop(2)
        for (b in badgeLm) {
            assertEquals(152.0, b.hueDeg, 1e-9)
            assertFalse(b.stemFound)
            assertEquals(b.centroidX, b.anchorX)
            // A badge must never be mistaken for a flower when matching between frames.
            for (f in r.hits) assertTrue(f.signatureDistance(b) > MapCalibration.MAX_SIGNATURE_DISTANCE)
        }
        // ...but badges do match each other.
        assertTrue(badgeLm[0].signatureDistance(badgeLm[1]) <= MapCalibration.MAX_SIGNATURE_DISTANCE)
    }

    @Test
    fun `tracker calibrates on two flowers plus badges, and only the flowers become waypoints`() {
        val tracker = ScanTracker()
        tracker.onFrame(sparseFrame(home), width, height, home, stationary = true)
        val p45 = GeoMath.offsetMeters(home, 45.0, 0.0)
        val o = tracker.onFrame(sparseFrame(p45), width, height, p45, stationary = true)
        assertTrue("$o", o is ScanTracker.Outcome.Scanning)
        val s = o as ScanTracker.Outcome.Scanning
        assertEquals(truth.metresPerPixel, s.calibration.metresPerPixel, 0.01)
        assertEquals("badges are landmarks, not flowers", 2, s.found.size)
        assertTrue(s.calibrationChanged!!.matches.size >= 5)
    }

    @Test
    fun `tracker calibrates a short shuttle from its two stationary ends`() {
        // The live patrol shuttled over ~55 m (two flowers 120 m apart, 30 m arrival radius each).
        // Moving frames in between never make a reference; the stationary frames at the ends do,
        // and 55 m apart is a valid baseline whatever path was walked between them.
        val tracker = ScanTracker()
        val legs = listOf(0.0, 15.0, 30.0, 45.0, 55.0, 40.0, 25.0, 10.0, 0.0)
        var first: ScanTracker.Outcome? = null
        for (northM in legs) {
            val pos = GeoMath.offsetMeters(home, northM, 0.0)
            val stationary = tracker.wantsStationaryFrame(pos)
            val o = tracker.onFrame(sparseFrame(pos), width, height, pos, stationary = stationary)
            if (o is ScanTracker.Outcome.Scanning && first == null) first = o
        }
        assertNotNull("shuttle should calibrate: last calibration=${tracker.calibration}", first)
        assertEquals(truth.metresPerPixel, tracker.calibration!!.metresPerPixel, 0.01)
    }

    @Test
    fun `blank frames - a black capture is reported, not mistaken for an empty map`() {
        val black = RgbImage.blank(1220, 2712)
        assertTrue(ScanTracker.isMostlyBlack(black))
        val grass = RgbImage.blank(1220, 2712, 0xFF6DB33F.toInt())
        assertFalse(ScanTracker.isMostlyBlack(grass))
        // A black frame with a small bright overlay bar on top is still black.
        val px = IntArray(1220 * 2712)
        for (y in 680..760) for (x in 0 until 1220) px[y * 1220 + x] = 0xFF2A2C30.toInt()
        for (y in 690..750) for (x in 100 until 600) px[y * 1220 + x] = 0xFFFFFFFF.toInt()
        assertTrue(ScanTracker.isMostlyBlack(RgbImage(1220, 2712, px)))

        val tracker = ScanTracker()
        for (i in 1..ScanTracker.BLANK_STREAK_LIMIT) {
            val o = tracker.onFrame(empty(), width, height, home, blank = true)
            assertTrue(o is ScanTracker.Outcome.Blank)
            assertEquals(i, (o as ScanTracker.Outcome.Blank).streak)
            assertEquals(i >= ScanTracker.BLANK_STREAK_LIMIT, o.exhausted)
        }
        // A real frame resets the streak and is processed normally.
        assertTrue(tracker.onFrame(frame(home), width, height, home, stationary = true) is ScanTracker.Outcome.Calibrating)
        assertEquals(1, (tracker.onFrame(empty(), width, height, home, blank = true) as ScanTracker.Outcome.Blank).streak)
    }

    // ---------------------------------------------------------------------------------------
    // RGBA byte packing with a padded row stride
    // ---------------------------------------------------------------------------------------

    @Test
    fun `rgba bytes - honours row and pixel stride`() {
        val w = 3
        val h = 2
        val pixelStride = 4
        val rowStride = 20   // 3 px * 4 B = 12 B of data, padded to 20
        val bytes = ByteArray(rowStride * h)
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int, a: Int) {
            val i = y * rowStride + x * pixelStride
            bytes[i] = r.toByte(); bytes[i + 1] = g.toByte(); bytes[i + 2] = b.toByte(); bytes[i + 3] = a.toByte()
        }
        put(0, 0, 0x11, 0x22, 0x33, 0xFF)
        put(1, 0, 0xAA, 0xBB, 0xCC, 0x80)
        put(2, 0, 0x01, 0x02, 0x03, 0x04)
        put(0, 1, 0xFF, 0x00, 0x00, 0xFF)
        put(1, 1, 0x00, 0xFF, 0x00, 0xFF)
        put(2, 1, 0x00, 0x00, 0xFF, 0xFF)
        // Poison the padding: it must never be read as a pixel.
        for (y in 0 until h) for (i in w * pixelStride until rowStride) bytes[y * rowStride + i] = 0x7F

        val img = RgbaBytes.toRgbImage(bytes, w, h, rowStride, pixelStride)
        assertEquals(0xFF112233.toInt(), img.get(0, 0))
        assertEquals(0x80AABBCC.toInt(), img.get(1, 0))
        assertEquals(0x04010203, img.get(2, 0))
        assertEquals(0xFFFF0000.toInt(), img.get(0, 1))
        assertEquals(0xFF00FF00.toInt(), img.get(1, 1))
        assertEquals(0xFF0000FF.toInt(), img.get(2, 1))
        assertEquals(255, img.red(0, 1))
        assertEquals(255, img.green(1, 1))
        assertEquals(255, img.blue(2, 1))
    }

    @Test
    fun `rgba bytes - last row may be unpadded`() {
        // A buffer whose final row stops right after its last pixel (no trailing padding) is legal.
        val w = 2
        val h = 2
        val rowStride = 12
        val bytes = ByteArray(rowStride + w * 4) { 0x10 }
        val img = RgbaBytes.toRgbImage(bytes, w, h, rowStride, 4)
        assertEquals(0x10101010, img.get(1, 1))
    }
}
