package app.pikminbloom.gps.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rectangle expressed as fractions of the frame (0..1), so the same [FlowerDetector.Params] work on
 * any screen size. `left`/`top` are inclusive, `right`/`bottom` exclusive.
 */
data class FracRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    /** True when the point ([x], [y]) of a [w] x [h] frame falls inside this region. */
    fun contains(x: Double, y: Double, w: Int, h: Int): Boolean {
        val fx = x / w
        val fy = y / h
        return fx >= left && fx < right && fy >= top && fy < bottom
    }
}

/** A connected component plus the colour statistics gathered while labelling it. */
data class Blob(
    val areaPx: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val centroidX: Double,
    val centroidY: Double,
    val meanHueDeg: Double,
    val meanSat: Double,
    val meanVal: Double,
) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1

    /** Long side over short side; 1.0 is square. */
    val aspect: Double get() = max(width, height).toDouble() / max(1, min(width, height)).toDouble()

    /** Component pixels over bounding-box pixels; a solid disc is ~0.79, a thin ring far less. */
    val fill: Double get() = areaPx.toDouble() / (width.toDouble() * height.toDouble())
}

/**
 * One accepted Big-Flower detection.
 *
 * @param anchorX x of the GROUND ANCHOR: where the stem meets the ground. This, not the bloom
 *        centroid, is the point that maps to a world coordinate — the bloom is drawn floating
 *        roughly 80-110 px above its map footprint at street zoom.
 * @param anchorY y of the ground anchor.
 * @param stemFound false when no stem column was located and the anchor fell back to
 *        "bloom centroid + [Params.fallbackDropFrac]". Such hits are usable but less accurate.
 */
data class FlowerHit(
    val centroidX: Int,
    val centroidY: Int,
    val anchorX: Int,
    val anchorY: Int,
    val areaPx: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val hueDeg: Double,
    val sat: Double,
    val value: Double,
    val stemFound: Boolean,
) {
    /**
     * Colour + size fingerprint used to match the same flower across two frames.
     * Hue is circular, so compare with [ColorMath.hueDistance], not plain subtraction.
     */
    fun signatureDistance(other: FlowerHit): Double {
        val dh = ColorMath.hueDistance(hueDeg, other.hueDeg) / 180.0
        val ds = abs(sat - other.sat)
        val dv = abs(value - other.value)
        val da = abs(areaPx - other.areaPx).toDouble() / max(areaPx, other.areaPx).toDouble()
        return sqrt(dh * dh * 4.0 + ds * ds + dv * dv + da * da * 0.5)
    }
}

/** A candidate component that did not survive, and the rule that killed it. */
data class Reject(val blob: Blob, val reason: String)

/** Everything the detector found, including the rejects, so a miss can be explained. */
data class DetectionResult(
    val hits: List<FlowerHit>,
    val candidates: Int,
    val rejectedByArea: Int,
    val rejectedByShape: Int,
    val rejectedByChrome: Int,
    val rejectedByMushroom: Int,
    val rejectedByAvatar: Int,
    val badges: List<Blob>,
    val avatars: List<Blob>,
    /** Every rejected candidate with its reason — for diagnostics, never for decisions. */
    val rejects: List<Reject> = emptyList(),
)

/**
 * HSV blob detector for Big Flowers in Pikmin Bloom's bird's-eye (map overview) mode.
 *
 * ## What the pixels actually look like
 * Every default here was set from measurements on the captured frames
 * (`app/src/test/resources/birdseye/ground_truth.md`), not from guesswork, and two of those
 * measurements shaped the whole design:
 *
 * * **Grass never leaves hue 63-121 degrees**, whatever its brightness, and its saturation runs as
 *   high as 0.53. So saturation cannot separate flower from ground — *hue* does. The primary test
 *   is "hue outside the grass band", with only a weak saturation floor behind it, because the pale
 *   violet bloom measures S ≈ 0.17 and any saturation gate strong enough to reject grass would
 *   throw that flower away.
 * * **The ground is carpeted with thousands of tiny planted flowers**, 3-6 px across, in exactly
 *   the same hues as the Big Flower blooms. Thresholding alone turns that carpet into a sea of
 *   speckle that chains into large components under 8-connectivity. The fix is a morphological
 *   opening: a pixel only *seeds* a blob if a [Params.erodeRadius]-radius square around it is also
 *   flower-coloured, which annihilates anything thinner than ~5 px while leaving a 20 px bloom
 *   head intact. Components are then grown on the full mask from those seeds
 *   (morphological reconstruction), so accepted blobs keep their true size.
 *
 * ## Pipeline
 *  1. classify every pixel (HSV thresholds);
 *  2. erode to seeds, then iterative 8-connected labelling of the full mask, keeping only
 *     components that contain a seed. The labelling uses an explicit index stack, never recursion —
 *     a 1220x2712 frame would overflow the JVM stack on the first large component;
 *  3. drop components by area, aspect ratio and fill;
 *  4. drop components whose centroid lies in a UI chrome band;
 *  5. drop components near a mushroom "person count" badge or a player-avatar pill;
 *  6. for each survivor walk down from the bloom's bottom edge looking for the stem -> ground anchor.
 *
 * ## The stem, honestly
 * Step 6 is the weakest link and it is worth being blunt about it. Measured on the capture set, a
 * stem pixel and the grass 12 px to its side differ by about **0.02 in value** and not at all in
 * hue or saturation. There is no colour threshold that finds a stem. The local-contrast scan below
 * does find one when the ground happens to be plain, but with the settings that stop it wandering
 * off it succeeds on roughly a quarter of detections; the rest fall back to a fixed drop below the
 * bloom centroid, calibrated from 15 hand measurements (median 85 px, range 67-107).
 *
 * That fallback is why the anchor error over the whole capture set is ~11 px (≈ 3.5 m) rather than
 * the ~27 m it would be if the bloom centroid were used directly. It also means the anchor's
 * accuracy is bounded by how much that offset varies between flower species, not by the detector.
 *
 * Pure Kotlin, no `android.*`, so all of it is JUnit-testable against PNG screenshots.
 */
class FlowerDetector(val params: Params = Params()) {

    /**
     * Tunables. Every default is documented with the measurement behind it so the numbers can be
     * re-tuned after a game art update without reading the detection logic.
     */
    data class Params(
        /**
         * Integer downscale applied before labelling. 1 = full resolution. All reported coordinates
         * and areas are always in the coordinate frame of the image handed to [detect], whatever
         * this is set to. 2 makes the labelling pass ~4x cheaper; it also halves the effective
         * erosion radius, so raise [erodeRadius] if you use it.
         */
        val downscale: Int = 1,

        // ---- pixel classification -------------------------------------------------------------
        /**
         * Grass hue band, degrees — the PRIMARY test. Measured grass (open, dense carpet, and the
         * pale roads, which are the same hue family) spans 63-121; the band is widened to 55-175
         * for margin and to swallow the darker tree canopies. A pixel inside this band is ground.
         */
        val grassHueMinDeg: Double = 55.0,
        val grassHueMaxDeg: Double = 175.0,
        /**
         * A green pixel this saturated is not grass — measured grass tops out near S 0.53.
         * Set above 1.0 to disable the override.
         */
        val grassOverrideSat: Double = 0.72,
        /**
         * Weak saturation floor. Deliberately low: the violet bloom measures S ≈ 0.17. Its job is
         * only to drop near-grey pixels (white UI, the avatar pill, road highlights) whose hue is
         * numerically meaningless.
         */
        val minSaturation: Double = 0.16,
        /** Value floor — drops shadow, the dark badges and dark decor. */
        val minValue: Double = 0.42,
        /** Value ceiling — drops blown-out white UI. */
        val maxValue: Double = 0.99,
        /**
         * Radius of the square structuring element used to find seeds. 3 means a 7x7 all-in test,
         * which erases the 3-6 px carpet speckle *and* the small clumps two or three of them form,
         * while keeping the >= 30 px bloom heads. 2 was measured to leave hundreds of clump
         * false positives per frame.
         */
        val erodeRadius: Int = 3,

        // ---- blob geometry --------------------------------------------------------------------
        /**
         * Smallest acceptable bloom, in pixels of the input frame. Measured true blooms at this
         * zoom run 815-2440 px; carpet clumps that survive the opening run 150-450 px. 650 sits in
         * the gap with room on both sides.
         */
        val minAreaPx: Int = 650,
        /** Largest acceptable bloom. Mushroom clusters, water and UI panels are far above this. */
        val maxAreaPx: Int = 5_000,
        /**
         * Long side / short side. A bloom is round (measured 1.0-1.6); the river fragments that
         * share the blue daisy's exact hue are elongated, and this is what separates them.
         */
        val maxAspect: Double = 2.0,
        /**
         * Component pixels / bounding-box pixels. A solid disc is ~0.79, but real blooms are not
         * discs: the frilly magenta carnation measures 0.33 and the violet bell 0.35-0.42, because
         * the gaps between their petals show grass. 0.30 is therefore about as high as this can go
         * without losing those two species outright — measured, 0.42 lost the carnation in every
         * frame it appeared in.
         */
        val minFill: Double = 0.30,
        /** Absolute cap on either bounding-box side, in input pixels. */
        val maxBoxSidePx: Int = 180,
        /**
         * Minimum for the SHORTER bounding-box side, in input pixels. Measured blooms are never
         * narrower than 35 px; this rejects the slivers that water and off-screen decor leave at
         * the frame edge, which can otherwise pass area, aspect and fill all at once.
         */
        val minBoxSidePx: Int = 30,
        /**
         * Minimum MEAN saturation of the whole component. This is a different test from the
         * per-pixel [minSaturation], which has to stay low (0.16) so the pale violet bloom's edge
         * pixels are admitted at all: a bloom is a saturated patch *on average* even when
         * individual pixels are washed out. Measured means — blooms 0.35-0.66, the player's Mii
         * FACE 0.22-0.23. The face is the single most persistent false positive in the capture set
         * (it appears in six of eight frames), and this is what removes it.
         */
        val minBlobMeanSat: Double = 0.28,

        // ---- UI chrome ------------------------------------------------------------------------
        /**
         * Screen regions that are never map content, as fractions of the frame. A blob is dropped
         * when its CENTROID falls inside one, so a bloom that merely brushes a button survives.
         * Defaults measured on the 1220x2712 reference screen but expressed fractionally.
         */
        val excludeRegions: List<FracRect> = DEFAULT_EXCLUSIONS,

        // ---- mushroom rejection ---------------------------------------------------------------
        /**
         * Mushroom clusters in this game ALWAYS carry a dark rounded "person count" pill and a
         * pie-timer disc, drawn 60-110 px above the caps. Any candidate within
         * [mushroomRejectRadiusFrac] of the frame width from such a badge is dropped.
         *
         * 0.13 of 1220 is 159 px. This is a genuine trade-off and it was measured: a cluster is
         * ~200 px wide so its outermost cap sits ~140 px from the badge, while the closest real
         * flower to a badge in the capture set is 215 px away (the magenta carnation in `be_1`).
         * A larger radius silently deletes that flower — which is exactly what 0.20 did.
         */
        val mushroomRejectRadiusFrac: Double = 0.13,
        /**
         * The badge pill is a dark teal-green rounded rect. Measured: H 148-156, S ≈ 0.40,
         * V ≈ 0.50. These bounds are deliberately tight — a false badge would silently delete a
         * real flower.
         */
        val badgeHueMinDeg: Double = 135.0,
        val badgeHueMaxDeg: Double = 178.0,
        val badgeMinSat: Double = 0.22,
        val badgeMaxSat: Double = 0.62,
        val badgeMinValue: Double = 0.36,
        val badgeMaxValue: Double = 0.64,
        /** Badge blob area window, in input pixels. */
        val badgeMinAreaPx: Int = 900,
        val badgeMaxAreaPx: Int = 14_000,
        /** A badge is a wide pill: at least this many times wider than tall. */
        val badgeMinAspect: Double = 1.15,
        val badgeMaxAspect: Double = 6.0,

        // ---- player avatar rejection ----------------------------------------------------------
        /** Avatar pills are near-white: value at or above this... */
        val avatarMinValue: Double = 0.86,
        /** ...and almost colourless. */
        val avatarMaxSat: Double = 0.16,
        /** The pill measures ~110 px across, so ~9 500 px; the window is generous around that. */
        val avatarMinAreaPx: Int = 3_000,
        val avatarMaxAreaPx: Int = 40_000,
        /** A pill is a compact disc; the pale roads that share its colour are long strips. */
        val avatarMaxAspect: Double = 2.2,
        val avatarMinFill: Double = 0.45,
        /**
         * Reject radius around an avatar pill, as a fraction of frame width. 0.10 of 1220 is 122 px
         * — enough to cover the pill itself (the Mii FACE inside it is skin-coloured, H≈35 S≈0.22
         * V≈0.90, and is otherwise a textbook false positive) plus its pointer.
         */
        val avatarRejectRadiusFrac: Double = 0.10,

        // ---- ground anchor (stem) -------------------------------------------------------------
        /**
         * How far below the bloom's bottom edge to look for the stem, as a fraction of frame
         * height. 0.055 of 2712 is 149 px, a little more than the 80-110 px measured at this zoom.
         */
        val stemMaxLenFrac: Double = 0.045,
        /**
         * Per-row search half-window, in pixels of the input frame. Deliberately tiny: the scan may
         * only step a few pixels left or right per row. A wide window lets "the darkest column"
         * wander off across the grass — measured, with a window of 0.85 x bloom width, anchors
         * ended up at x = 0 and x = 1215 on a 1220-wide frame.
         */
        val stemStepPx: Int = 3,
        /** Total sideways drift allowed over the whole scan, as a fraction of the bloom's width. */
        val stemMaxDriftFrac: Double = 0.60,
        /** Half-width of the window used to estimate local grass brightness, in input pixels. */
        val stemReferenceHalfWidthPx: Int = 26,
        /**
         * A stem is found by LOCAL CONTRAST, not by an absolute colour: measured stem pixels are
         * statistically identical to the grass 12 px beside them (same hue, same saturation, value
         * lower by only ~0.02). A column counts as stem when it is at least this much darker than
         * the median of the reference window around it.
         */
        val stemMinContrast: Double = 0.055,
        /** The stem must also still be green — this rejects following a road or a shadow edge. */
        val stemHueMinDeg: Double = 50.0,
        val stemHueMaxDeg: Double = 180.0,
        /** Rows the scan may miss before giving up. */
        val stemGapTolerance: Int = 4,
        /**
         * Minimum rows of stem before the anchor is trusted, as a fraction of frame height.
         * 0.020 of 2712 is 54 px — over half a measured stem, so a handful of dark grass pixels
         * cannot fake one.
         */
        val stemMinRowsFrac: Double = 0.020,
        /**
         * Where the ground anchor goes when no stem is found: a drop below the BLOOM CENTROID,
         * as a fraction of frame height.
         *
         * 0.0313 of 2712 is 85 px, the median of the 15 hand-measured bloom-centre-to-stem-base
         * offsets in the capture set (range 67-107). Measuring from the centroid rather than the
         * bounding-box bottom matters because the blob's extent depends on how much of the bloom
         * clears the colour thresholds, whereas the centroid is stable.
         *
         * This offset is in SCREEN pixels and therefore depends on map zoom: 85 px is 27 m at the
         * measured 0.315 m/px. Re-measure it if the feature is ever used at another zoom.
         */
        val fallbackDropFrac: Double = 0.0313,
    ) {
        init {
            require(downscale >= 1) { "downscale must be >= 1" }
            require(erodeRadius >= 0) { "erodeRadius must be >= 0" }
            require(minAreaPx in 1 until maxAreaPx) { "bad area window $minAreaPx..$maxAreaPx" }
        }

        companion object {
            /**
             * Bird's-eye chrome on the reference device (1220x2712). Deliberately made of small
             * boxes around the actual controls rather than full-width bands: real flowers do appear
             * low in the frame, and a full-width bottom band would delete them.
             */
            val DEFAULT_EXCLUSIONS: List<FracRect> = listOf(
                FracRect(0.00, 0.000, 1.00, 0.045),   // Android status bar
                FracRect(0.84, 0.045, 1.00, 0.130),   // north compass button (top right)
                FracRect(0.32, 0.915, 0.68, 1.000),   // bottom two-button pill
                FracRect(0.00, 0.915, 0.17, 1.000),   // bottom-left back button
                FracRect(0.83, 0.915, 1.00, 1.000),   // bottom-right recenter button
            )
        }
    }

    /** Runs the whole pipeline over [image]. */
    fun detect(image: RgbImage): DetectionResult {
        val f = params.downscale
        val work = if (f == 1) image else image.downscale(f)
        val w = work.width
        val h = work.height
        val areaScale = (f * f).toDouble()
        val minArea = max(1, (params.minAreaPx / areaScale).toInt())
        val maxArea = max(minArea + 1, (params.maxAreaPx / areaScale).toInt())

        val hsv = DoubleArray(3)

        // ---- 1. candidate mask ---------------------------------------------------------------
        val mask = BooleanArray(w * h)
        for (i in 0 until w * h) {
            ColorMath.toHsv(work.pixels[i], hsv)
            mask[i] = isFlowerPixel(hsv[0], hsv[1], hsv[2])
        }

        // ---- 2. seeds (morphological erosion) + reconstruction --------------------------------
        val seeds = erode(mask, w, h, params.erodeRadius)
        val blobs = labelWithSeeds(work, mask, seeds, minArea, maxArea)

        // ---- rejector support masks -----------------------------------------------------------
        val badges = findBlobs(work) { hu, s, v ->
            hu >= params.badgeHueMinDeg && hu <= params.badgeHueMaxDeg &&
                s >= params.badgeMinSat && s <= params.badgeMaxSat &&
                v >= params.badgeMinValue && v <= params.badgeMaxValue
        }.filter {
            val a = it.areaPx * areaScale
            a >= params.badgeMinAreaPx && a <= params.badgeMaxAreaPx &&
                it.width >= it.height &&
                it.aspect >= params.badgeMinAspect && it.aspect <= params.badgeMaxAspect
        }.filterNot { blob ->
            // The bottom-left back button and the bottom-right recenter button are dark green
            // discs that pass the badge colour test. A false badge silently deletes every real
            // flower within the reject radius, so screen furniture must never become one.
            params.excludeRegions.any { it.contains(blob.centroidX, blob.centroidY, w, h) }
        }
        val avatars = findBlobs(work) { _, s, v ->
            v >= params.avatarMinValue && s <= params.avatarMaxSat
        }.filter {
            val a = it.areaPx * areaScale
            a >= params.avatarMinAreaPx && a <= params.avatarMaxAreaPx &&
                // Roads are pale and desaturated too, and there are far more of them than avatars.
                // What separates them is shape: the pill is a compact disc, a road is a long strip.
                it.aspect <= params.avatarMaxAspect && it.fill >= params.avatarMinFill
        }.filterNot { blob ->
            params.excludeRegions.any { it.contains(blob.centroidX, blob.centroidY, w, h) }
        }

        val mushroomR = params.mushroomRejectRadiusFrac * w
        val avatarR = params.avatarRejectRadiusFrac * w

        var rejArea = 0
        var rejShape = 0
        var rejChrome = 0
        var rejMush = 0
        var rejAvatar = 0
        val hits = ArrayList<FlowerHit>()
        val rejects = ArrayList<Reject>()

        for (b in blobs) {
            val scaled = b.scaled(f)
            if (b.areaPx < minArea || b.areaPx > maxArea) {
                rejArea++
                rejects += Reject(scaled, "area ${b.areaPx * f * f} outside ${params.minAreaPx}..${params.maxAreaPx}")
                continue
            }
            if (b.aspect > params.maxAspect || b.fill < params.minFill ||
                max(b.width, b.height) * f > params.maxBoxSidePx ||
                min(b.width, b.height) * f < params.minBoxSidePx ||
                b.meanSat < params.minBlobMeanSat
            ) {
                rejShape++
                rejects += Reject(
                    scaled,
                    "shape aspect=%.2f fill=%.2f box=%dx%d meanSat=%.2f".format(
                        b.aspect, b.fill, b.width * f, b.height * f, b.meanSat,
                    ),
                )
                continue
            }
            if (params.excludeRegions.any { it.contains(b.centroidX, b.centroidY, w, h) }) {
                rejChrome++
                rejects += Reject(scaled, "UI chrome")
                continue
            }
            val badge = badges.firstOrNull {
                dist(b.centroidX, b.centroidY, it.centroidX, it.centroidY) <= mushroomR
            }
            if (badge != null) {
                rejMush++
                rejects += Reject(
                    scaled,
                    "mushroom badge at (${(badge.centroidX * f).toInt()},${(badge.centroidY * f).toInt()}) " +
                        "%.0f px away".format(dist(b.centroidX, b.centroidY, badge.centroidX, badge.centroidY) * f),
                )
                continue
            }
            val avatar = avatars.firstOrNull {
                dist(b.centroidX, b.centroidY, it.centroidX, it.centroidY) <= avatarR
            }
            if (avatar != null) {
                rejAvatar++
                rejects += Reject(
                    scaled,
                    "avatar pill at (${(avatar.centroidX * f).toInt()},${(avatar.centroidY * f).toInt()}) " +
                        "%.0f px away".format(dist(b.centroidX, b.centroidY, avatar.centroidX, avatar.centroidY) * f),
                )
                continue
            }
            hits += toHit(work, b, f)
        }

        return DetectionResult(
            hits = hits.sortedBy { it.anchorY },
            candidates = blobs.size,
            rejectedByArea = rejArea,
            rejectedByShape = rejShape,
            rejectedByChrome = rejChrome,
            rejectedByMushroom = rejMush,
            rejectedByAvatar = rejAvatar,
            badges = badges.map { it.scaled(f) },
            avatars = avatars.map { it.scaled(f) },
            rejects = rejects,
        )
    }

    /** Pixel-level classifier, exposed so tests can probe individual colours. */
    fun isFlowerPixel(hueDeg: Double, sat: Double, value: Double): Boolean {
        if (sat < params.minSaturation) return false
        if (value < params.minValue || value > params.maxValue) return false
        val greenish = hueDeg >= params.grassHueMinDeg && hueDeg <= params.grassHueMaxDeg
        return !greenish || sat >= params.grassOverrideSat
    }

    // ------------------------------------------------------------------------------------------
    // Morphology
    // ------------------------------------------------------------------------------------------

    /**
     * Erosion by a `(2r+1)` square: a pixel survives only when every pixel of that square is set.
     * Implemented as two separable passes (horizontal then vertical run-length), so the cost is
     * O(w*h) regardless of r rather than O(w*h*r^2).
     */
    private fun erode(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return mask.copyOf()
        val horiz = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            // run[x] = number of consecutive set pixels ending at x
            var run = 0
            val runs = IntArray(w)
            for (x in 0 until w) {
                run = if (mask[row + x]) run + 1 else 0
                runs[x] = run
            }
            for (x in r until w - r) {
                // the window [x-r, x+r] is all set iff the run ending at x+r is at least 2r+1 long
                horiz[row + x] = runs[x + r] >= 2 * r + 1
            }
        }
        val out = BooleanArray(w * h)
        val runs = IntArray(h)
        for (x in 0 until w) {
            var run = 0
            for (y in 0 until h) {
                run = if (horiz[y * w + x]) run + 1 else 0
                runs[y] = run
            }
            for (y in r until h - r) {
                out[y * w + x] = runs[y + r] >= 2 * r + 1
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // Connected components
    // ------------------------------------------------------------------------------------------

    private inline fun findBlobs(
        img: RgbImage,
        crossinline predicate: (hue: Double, sat: Double, value: Double) -> Boolean,
    ): List<Blob> {
        val hsv = DoubleArray(3)
        val m = BooleanArray(img.width * img.height)
        for (i in m.indices) {
            ColorMath.toHsv(img.pixels[i], hsv)
            m[i] = predicate(hsv[0], hsv[1], hsv[2])
        }
        return labelWithSeeds(img, m, seeds = null, minArea = 1, maxArea = Int.MAX_VALUE)
    }

    /**
     * Iterative 8-connected labelling over [mask].
     *
     * When [seeds] is non-null a component is only returned if it contains at least one seed pixel
     * (morphological reconstruction) — the component is still traversed either way, so its pixels
     * cannot seed another component.
     *
     * Uses an explicit `IntArray` stack of pixel indices. Recursion is not an option: one water
     * body on a 1220x2712 frame can be hundreds of thousands of pixels deep.
     */
    private fun labelWithSeeds(
        img: RgbImage,
        mask: BooleanArray,
        seeds: BooleanArray?,
        minArea: Int,
        maxArea: Int,
    ): List<Blob> {
        val w = img.width
        val h = img.height
        val visited = BooleanArray(w * h)
        var stack = IntArray(4096)
        val out = ArrayList<Blob>()
        val hsv = DoubleArray(3)

        for (start in 0 until w * h) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true

            var area = 0
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var sumX = 0L
            var sumY = 0L
            // Hue is circular: average it as a unit vector, not as a scalar.
            var hueX = 0.0
            var hueY = 0.0
            var sumS = 0.0
            var sumV = 0.0
            var hasSeed = seeds == null

            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                sumX += x
                sumY += y
                if (seeds != null && seeds[idx]) hasSeed = true
                ColorMath.toHsv(img.pixels[idx], hsv)
                val rad = hsv[0] * Math.PI / 180.0
                hueX += Math.cos(rad)
                hueY += Math.sin(rad)
                sumS += hsv[1]
                sumV += hsv[2]

                var dy = -1
                while (dy <= 1) {
                    val ny = y + dy
                    if (ny in 0 until h) {
                        var dx = -1
                        while (dx <= 1) {
                            val nx = x + dx
                            if ((dx != 0 || dy != 0) && nx in 0 until w) {
                                val nIdx = ny * w + nx
                                if (mask[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                                    stack[sp++] = nIdx
                                }
                            }
                            dx++
                        }
                    }
                    dy++
                }
            }

            if (!hasSeed) continue
            if (area < minArea || area > maxArea) continue
            var meanHue = Math.atan2(hueY / area, hueX / area) * 180.0 / Math.PI
            if (meanHue < 0.0) meanHue += 360.0
            out += Blob(
                areaPx = area,
                left = minX, top = minY, right = maxX, bottom = maxY,
                centroidX = sumX.toDouble() / area,
                centroidY = sumY.toDouble() / area,
                meanHueDeg = meanHue,
                meanSat = sumS / area,
                meanVal = sumV / area,
            )
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // Ground anchor
    // ------------------------------------------------------------------------------------------

    /**
     * Turns a bloom blob into a [FlowerHit], scanning downward for the stem.
     *
     * The bloom floats above its map footprint; the point that corresponds to a lat/lon is where
     * the stem meets the ground. Because a stem pixel is *not* distinguishable from grass by
     * absolute colour (measured: same hue, same saturation, 0.02 lower value), the scan works on
     * local contrast: for each row below the bloom it compares the darkest green column within
     * [Params.stemStepPx] of where the stem was on the previous row against the median of a wider
     * reference window, and requires it to be at least [Params.stemMinContrast] darker. The narrow
     * step and the [Params.stemMaxDriftFrac] cap are what keep the scan on the stem instead of
     * letting it walk away across the grass. The last row that still had a stem wins.
     *
     * When the scan fails the anchor falls back to the bloom centroid plus
     * [Params.fallbackDropFrac] of the frame height. In the capture set this fallback is the
     * common case — see the honest assessment in the class docs of the stem's detectability.
     */
    private fun toHit(img: RgbImage, b: Blob, scale: Int): FlowerHit {
        val hsv = DoubleArray(3)
        val maxLen = max(3, (params.stemMaxLenFrac * img.height).toInt())
        val step = max(1, params.stemStepPx)
        val refHalf = max(step + 2, params.stemReferenceHalfWidthPx)
        val maxDrift = max(2, (params.stemMaxDriftFrac * b.width).toInt())

        val startX = ((b.left + b.right) / 2.0).toInt()
        var cx = startX
        var lastY = b.bottom
        var rows = 0
        var gap = 0
        var y = b.bottom + 1
        val limit = min(img.height - 1, b.bottom + maxLen)
        val ref = DoubleArray(2 * refHalf + 1)

        while (y <= limit) {
            // Reference brightness: the median over a wide window, i.e. "what the grass here looks
            // like". A narrow window would be dominated by the stem itself.
            val r0 = max(0, cx - refHalf)
            val r1 = min(img.width - 1, cx + refHalf)
            val rn = r1 - r0 + 1
            if (rn < 5) break
            for (i in 0 until rn) {
                ColorMath.toHsv(img.get(r0 + i, y), hsv)
                ref[i] = hsv[2]
            }
            val median = medianOf(ref, rn)

            // Candidate: the darkest still-green column within one small step of the last one.
            var bestX = -1
            var bestV = Double.MAX_VALUE
            var x = max(0, cx - step)
            val xEnd = min(img.width - 1, cx + step)
            while (x <= xEnd) {
                ColorMath.toHsv(img.get(x, y), hsv)
                val greenEnough = hsv[0] >= params.stemHueMinDeg && hsv[0] <= params.stemHueMaxDeg
                if (greenEnough && hsv[2] < bestV) { bestV = hsv[2]; bestX = x }
                x++
            }

            if (bestX >= 0 && abs(bestX - startX) <= maxDrift &&
                median - bestV >= params.stemMinContrast
            ) {
                cx = bestX
                lastY = y
                rows++
                gap = 0
            } else {
                gap++
                if (gap > params.stemGapTolerance) break
            }
            y++
        }

        val minRows = max(4, (params.stemMinRowsFrac * img.height).toInt())
        val found = rows >= minRows
        val fallbackDrop = (params.fallbackDropFrac * img.height).toInt()
        val anchorX = if (found) cx else b.centroidX.toInt()
        val anchorY =
            if (found) lastY else min(img.height - 1, b.centroidY.toInt() + fallbackDrop)

        return FlowerHit(
            centroidX = (b.centroidX * scale).toInt(),
            centroidY = (b.centroidY * scale).toInt(),
            anchorX = anchorX * scale,
            anchorY = anchorY * scale,
            areaPx = b.areaPx * scale * scale,
            left = b.left * scale,
            top = b.top * scale,
            right = b.right * scale,
            bottom = b.bottom * scale,
            hueDeg = b.meanHueDeg,
            sat = b.meanSat,
            value = b.meanVal,
            stemFound = found,
        )
    }

    private fun medianOf(buf: DoubleArray, n: Int): Double {
        val copy = buf.copyOf(n)
        copy.sort()
        return if (n % 2 == 1) copy[n / 2] else (copy[n / 2 - 1] + copy[n / 2]) / 2.0
    }

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}

private fun Blob.scaled(f: Int): Blob =
    if (f == 1) this else copy(
        areaPx = areaPx * f * f,
        left = left * f, top = top * f, right = right * f, bottom = bottom * f,
        centroidX = centroidX * f, centroidY = centroidY * f,
    )
