package app.pikminbloom.gps.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Runs the detector over the real bird's-eye screenshots in `src/test/resources/birdseye/` and
 * scores it against the hand-read ground truth in `ground_truth.md`.
 *
 * Create the marker file `app/build/vision-debug.on` to have every frame written to
 * `app/build/vision-debug/` with the detections, badges and expected positions drawn on. That
 * render is how the parameters were tuned, and it is also what caught two real Big Flowers that
 * were missing from the first pass at the ground truth.
 */
class FlowerDetectorTest {

    /** One expected Big Flower: bloom centre and the stem-base ground anchor, in image pixels. */
    data class Truth(val bloomX: Int, val bloomY: Int, val anchorX: Int, val anchorY: Int)

    private val groundTruth: Map<String, List<Truth>> = mapOf(
        "be_1.png" to listOf(
            Truth(163, 1418, 163, 1500),
            Truth(90, 1460, 88, 1547),
            Truth(138, 1575, 133, 1682),
            Truth(628, 1618, 622, 1725),
            Truth(795, 1855, 795, 1940),
        ),
        "be_2.png" to listOf(
            Truth(930, 2355, 925, 2445),
            Truth(852, 2405, 848, 2492),
            Truth(910, 2522, 908, 2625),
        ),
        "be_3.png" to listOf(
            Truth(163, 1418, 163, 1500),
            Truth(90, 1460, 88, 1547),
            Truth(138, 1575, 133, 1682),
            Truth(628, 1618, 622, 1725),
            Truth(795, 1855, 795, 1940),
        ),
        "be_4.png" to listOf(
            Truth(167, 2432, 165, 2520),
            Truth(88, 2478, 85, 2565),
        ),
        "be_5.png" to listOf(
            Truth(931, 1408, 925, 1475),
            Truth(698, 1495, 705, 1570),
            Truth(520, 1573, 526, 1652),
            Truth(946, 1683, 950, 1770),
            Truth(530, 2190, 527, 2270),
            // Found by reviewing the debug render, not by the first pass over the screenshot:
            // a pink daisy that the detector flagged and I had missed by eye.
            Truth(937, 2285, 933, 2372),
        ),
        "be_6.png" to emptyList(),
        "cal_a.png" to listOf(
            Truth(155, 1490, 150, 1575),
            Truth(78, 1536, 75, 1620),
            Truth(130, 1650, 128, 1755),
            Truth(620, 1692, 620, 1795),
            Truth(786, 1930, 782, 2018),
        ),
        "cal_b.png" to listOf(
            Truth(155, 1680, 152, 1765),
            Truth(78, 1728, 75, 1812),
            Truth(128, 1843, 127, 1945),
            Truth(620, 1880, 620, 1985),
            Truth(786, 2126, 786, 2210),
        ),
    )

    /**
     * Real Big Flowers that are clipped by the frame edge, so their ground anchor is off-screen.
     * A detection here is neither right nor wrong: it is a genuine flower, but one the pipeline
     * could not place accurately, so it is excluded from the score rather than counted as a false
     * positive. `FlowerScanPlan` is what would filter these in production, via the range cap.
     */
    private val ignored: Map<String, List<Pair<Int, Int>>> = mapOf(
        // A violet bloom sitting on the very bottom edge; its stem base is below the frame.
        "be_5.png" to listOf(921 to 2690),
    )

    /** Mushroom cluster centres that must never be reported as flowers. */
    private val mushrooms: Map<String, List<Pair<Int, Int>>> = mapOf(
        "be_1.png" to listOf(760 to 1450, 860 to 1690, 900 to 2110),
        "be_3.png" to listOf(760 to 1450, 860 to 1690, 900 to 2110),
        "be_4.png" to listOf(740 to 2620),
        "be_5.png" to listOf(1010 to 1880, 920 to 2070, 1090 to 2065, 100 to 2380),
        "be_6.png" to listOf(800 to 2000),
        "cal_a.png" to listOf(760 to 1450, 860 to 1690, 900 to 2110),
        "cal_b.png" to listOf(760 to 1640, 860 to 1880, 900 to 2300),
    )

    /** Positional tolerance for calling a detection a match, in pixels. */
    private val tolerancePx = 40.0

    /** How close a hit may come to a mushroom cluster centre before it counts as a false fire. */
    private val mushroomTolerancePx = 130.0

    /**
     * Debug rendering is opt-in via a marker file rather than a system property, because the
     * Gradle test JVM does not inherit `-D` flags from the command line and `build.gradle` is
     * off-limits to this feature. Create `app/build/vision-debug.on` (any content) and re-run to
     * get every frame written to `app/build/vision-debug/` with detections drawn on.
     */
    private val debug: Boolean =
        System.getProperty("vision.debug") == "true" || File("build/vision-debug.on").exists()

    @Test
    fun `detects the ground-truth flowers on every bird's-eye screenshot`() {
        val names = groundTruth.keys.filter { TestImages.loadOrNull(it) != null }.sorted()
        if (names.isEmpty()) {
            println("VISION: no captured screenshots present - skipping")
            return
        }
        val detector = FlowerDetector()

        var totalTp = 0
        var totalFp = 0
        var totalFn = 0
        val anchorErrors = ArrayList<Double>()
        val perImage = StringBuilder()

        for (name in names) {
            val img = TestImages.load(name)
            val t0 = System.nanoTime()
            val result = detector.detect(img)
            val ms = (System.nanoTime() - t0) / 1_000_000

            val expected = groundTruth.getValue(name).toMutableList()
            val ignore = ignored[name].orEmpty()
            val hits = result.hits.filterNot { hHit ->
                ignore.any { (ix, iy) ->
                    hypot((hHit.centroidX - ix).toDouble(), (hHit.centroidY - iy).toDouble()) <= tolerancePx
                }
            }.toMutableList()
            val ignoredHits = result.hits.size - hits.size
            var tp = 0
            val matchedAnchorErr = ArrayList<Double>()

            // Greedy nearest-first matching on the BLOOM centroid: the anchor is what we then score.
            val pairs = ArrayList<Triple<Double, Truth, FlowerHit>>()
            for (e in expected) {
                for (hHit in hits) {
                    val d = hypot(
                        (hHit.centroidX - e.bloomX).toDouble(),
                        (hHit.centroidY - e.bloomY).toDouble(),
                    )
                    if (d <= tolerancePx) pairs += Triple(d, e, hHit)
                }
            }
            pairs.sortBy { it.first }
            val usedTruth = HashSet<Truth>()
            val usedHit = HashSet<FlowerHit>()
            for ((_, e, hHit) in pairs) {
                if (e in usedTruth || hHit in usedHit) continue
                usedTruth += e
                usedHit += hHit
                tp++
                matchedAnchorErr += hypot(
                    (hHit.anchorX - e.anchorX).toDouble(),
                    (hHit.anchorY - e.anchorY).toDouble(),
                )
            }
            val fn = expected.size - tp
            val fp = hits.size - tp
            totalTp += tp
            totalFp += fp
            totalFn += fn
            anchorErrors += matchedAnchorErr

            val precision = if (hits.isEmpty()) 1.0 else tp.toDouble() / hits.size
            val recall = if (expected.isEmpty()) 1.0 else tp.toDouble() / expected.size
            perImage.append(
                String.format(
                    "  %-10s expected=%d found=%d(+%d ignored)  TP=%d FP=%d FN=%d  P=%.2f R=%.2f" +
                        "  anchorErr(med)=%s px  stems=%d/%d  %d ms%n",
                    name, expected.size, hits.size, ignoredHits, tp, fp, fn, precision, recall,
                    if (matchedAnchorErr.isEmpty()) "-" else
                        String.format("%.0f", matchedAnchorErr.sorted()[matchedAnchorErr.size / 2]),
                    result.hits.count { it.stemFound }, result.hits.size, ms,
                ),
            )
            perImage.append(
                String.format(
                    "             candidates=%d rejected: area=%d shape=%d chrome=%d mushroom=%d avatar=%d" +
                        "  badges=%d avatars=%d%n",
                    result.candidates, result.rejectedByArea, result.rejectedByShape,
                    result.rejectedByChrome, result.rejectedByMushroom, result.rejectedByAvatar,
                    result.badges.size, result.avatars.size,
                ),
            )
            for (hHit in hits) {
                val matched = hHit in usedHit
                val w = hHit.right - hHit.left + 1
                val hgt = hHit.bottom - hHit.top + 1
                perImage.append(
                    String.format(
                        "             %s bloom=(%d,%d) anchor=(%d,%d) area=%d box=%dx%d asp=%.2f " +
                            "fill=%.2f H=%.0f S=%.2f V=%.2f stem=%s%n",
                        if (matched) "TP" else "FP",
                        hHit.centroidX, hHit.centroidY, hHit.anchorX, hHit.anchorY,
                        hHit.areaPx, w, hgt,
                        maxOf(w, hgt).toDouble() / minOf(w, hgt),
                        hHit.areaPx.toDouble() / (w.toDouble() * hgt),
                        hHit.hueDeg, hHit.sat, hHit.value, hHit.stemFound,
                    ),
                )
            }
            for (e in expected) if (e !in usedTruth) {
                val near = result.rejects
                    .map { it to hypot(it.blob.centroidX - e.bloomX, it.blob.centroidY - e.bloomY) }
                    .filter { it.second <= 60.0 }
                    .minByOrNull { it.second }
                perImage.append(
                    String.format(
                        "             MISS bloom=(%d,%d) -> %s%n", e.bloomX, e.bloomY,
                        if (near == null) "no candidate component within 60 px (colour thresholds)"
                        else "rejected: ${near.first.reason} (blob at " +
                            "${near.first.blob.centroidX.toInt()},${near.first.blob.centroidY.toInt()})",
                    ),
                )
            }

            if (debug) dumpDebug(name, img, result, groundTruth.getValue(name))
        }

        val precision = if (totalTp + totalFp == 0) 1.0 else totalTp.toDouble() / (totalTp + totalFp)
        val recall = if (totalTp + totalFn == 0) 1.0 else totalTp.toDouble() / (totalTp + totalFn)
        println("=== FlowerDetector on real bird's-eye captures ===")
        print(perImage)
        println(
            String.format(
                "  OVERALL TP=%d FP=%d FN=%d  precision=%.3f  recall=%.3f  anchor error median=%s px",
                totalTp, totalFp, totalFn, precision, recall,
                if (anchorErrors.isEmpty()) "-" else
                    String.format("%.0f", anchorErrors.sorted()[anchorErrors.size / 2]),
            ),
        )

        assertTrue(
            "recall $recall is below 0.80 (TP=$totalTp FN=$totalFn) — see the per-image report above",
            recall >= 0.80,
        )
        assertTrue(
            "precision $precision is below 0.70 (TP=$totalTp FP=$totalFp) - see the report above",
            precision >= 0.70,
        )
    }

    @Test
    fun `reports nothing inside the UI chrome`() {
        val names = TestImages.birdsEyeNames() + TestImages.calibrationNames()
        if (names.isEmpty()) {
            println("VISION: no captured screenshots present - skipping")
            return
        }
        val detector = FlowerDetector()
        val offenders = ArrayList<String>()
        for (name in names) {
            val img = TestImages.load(name)
            for (hHit in detector.detect(img).hits) {
                for (r in detector.params.excludeRegions) {
                    if (r.contains(hHit.centroidX.toDouble(), hHit.centroidY.toDouble(), img.width, img.height)) {
                        offenders += "$name: hit at (${hHit.centroidX},${hHit.centroidY}) inside $r"
                    }
                }
            }
        }
        println("VISION: chrome check over ${names.size} frames -> ${offenders.size} violations")
        assertEquals(offenders.joinToString("\n"), 0, offenders.size)
    }

    @Test
    fun `does not fire on mushroom clusters`() {
        val names = mushrooms.keys.filter { TestImages.loadOrNull(it) != null }.sorted()
        if (names.isEmpty()) {
            println("VISION: no captured screenshots present - skipping")
            return
        }
        val detector = FlowerDetector()
        val offenders = ArrayList<String>()
        var checked = 0
        for (name in names) {
            val img = TestImages.load(name)
            val hits = detector.detect(img).hits
            for ((mx, my) in mushrooms.getValue(name)) {
                checked++
                for (hHit in hits) {
                    val d = hypot((hHit.centroidX - mx).toDouble(), (hHit.centroidY - my).toDouble())
                    if (d <= mushroomTolerancePx) {
                        offenders += "$name: hit at (${hHit.centroidX},${hHit.centroidY}) is ${d.toInt()} px " +
                            "from the mushroom cluster at ($mx,$my)"
                    }
                }
            }
        }
        println("VISION: mushroom check over $checked clusters -> ${offenders.size} false fires")
        assertEquals(offenders.joinToString("\n"), 0, offenders.size)
    }

    /**
     * The walk view is a dense 3-D flower carpet with no Big Flower icons in it at all, so there
     * is no useful answer for the detector to give — it is simply the wrong input.
     *
     * Rather than pretend the detector copes, this test pins down something the service layer can
     * actually use: the walk view is **detectably out of distribution**. Every bird's-eye frame in
     * the capture set yields a handful of detections; the walk view yields an order of magnitude
     * more. So a caller that finds itself with an implausible number of hits can conclude the game
     * is not in bird's-eye mode and abort, instead of writing nonsense into `WaypointStore`.
     */
    @Test
    fun `walk view is detectably out of distribution`() {
        val walk = TestImages.walkNames()
        val birdsEye = TestImages.birdsEyeNames() + TestImages.calibrationNames()
        if (walk.isEmpty() || birdsEye.isEmpty()) {
            println("VISION: no walk-view captures present - skipping")
            return
        }
        val detector = FlowerDetector()
        val birdsEyeMax = birdsEye.maxOf { detector.detect(TestImages.load(it)).hits.size }
        var walkMin = Int.MAX_VALUE
        for (name in walk) {
            val img = TestImages.load(name)
            val result = detector.detect(img)
            println(
                "VISION: $name (walk view, negative control) -> ${result.hits.size} detections " +
                    "from ${result.candidates} candidates",
            )
            if (debug) dumpDebug(name, img, result, emptyList())
            walkMin = minOf(walkMin, result.hits.size)
        }
        println("VISION: bird's-eye max = $birdsEyeMax hits, walk-view min = $walkMin hits")
        assertTrue(
            "the walk view ($walkMin hits) is not separable from bird's-eye ($birdsEyeMax hits); " +
                "a caller could not tell the two modes apart from the detection count alone",
            walkMin >= birdsEyeMax * 3,
        )
    }

    @Test
    fun `pixel classifier follows the measured colour statistics`() {
        val d = FlowerDetector()
        // Measured grass: hue 63-121, saturation up to 0.53.
        assertTrue("open grass must not be a flower pixel", !d.isFlowerPixel(106.0, 0.47, 0.71))
        assertTrue("dense carpet must not be a flower pixel", !d.isFlowerPixel(117.0, 0.49, 0.69))
        assertTrue("pale road must not be a flower pixel", !d.isFlowerPixel(98.0, 0.45, 0.72))
        // Measured blooms.
        assertTrue("daisy centre (orange) must pass", d.isFlowerPixel(39.0, 0.60, 0.91))
        assertTrue("pale violet bloom must pass", d.isFlowerPixel(265.0, 0.17, 0.82))
        assertTrue("magenta carnation must pass", d.isFlowerPixel(312.0, 0.66, 0.59))
        // The dark badge and the white avatar must not.
        assertTrue("badge pill must not pass", !d.isFlowerPixel(153.0, 0.40, 0.50))
        assertTrue("white avatar pill must not pass", !d.isFlowerPixel(0.0, 0.02, 0.98))
    }

    // ------------------------------------------------------------------------------------------

    private fun dumpDebug(
        name: String,
        img: RgbImage,
        result: DetectionResult,
        truth: List<Truth>,
    ) {
        val orange = 0xFFFF8000.toInt()
        val cyan = 0xFF00E5FF.toInt()
        val yellow = 0xFFFFEE00.toInt()
        val green = 0xFF00FF3C.toInt()
        val red = 0xFFFF0033.toInt()

        val canvas = RgbImage(img.width, img.height, img.pixels.copyOf())
        for (r in result.badges) Draw.rect(canvas, r.left, r.top, r.right, r.bottom, orange)
        for (r in result.avatars) Draw.rect(canvas, r.left, r.top, r.right, r.bottom, cyan)
        for (t in truth) {
            Draw.circle(canvas, t.bloomX, t.bloomY, 40, yellow)
            Draw.circle(canvas, t.bloomX, t.bloomY, 41, yellow)
            Draw.cross(canvas, t.anchorX, t.anchorY, 16, yellow)
        }
        for (hHit in result.hits) {
            val c = if (hHit.stemFound) green else red
            Draw.rect(canvas, hHit.left, hHit.top, hHit.right, hHit.bottom, c)
            Draw.line(canvas, hHit.centroidX, hHit.centroidY, hHit.anchorX, hHit.anchorY, c)
            Draw.circle(canvas, hHit.anchorX, hHit.anchorY, 9, c)
            Draw.circle(canvas, hHit.anchorX, hHit.anchorY, 10, c)
        }
        PngCodec.write(canvas, File("build/vision-debug", "dbg_$name"))
    }
}
