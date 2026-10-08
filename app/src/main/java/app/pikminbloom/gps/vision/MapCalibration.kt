package app.pikminbloom.gps.vision


import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in screen pixels. x grows right, y grows DOWN, as in every raster. */
data class PixelPoint(val x: Double, val y: Double) {
    constructor(x: Int, y: Int) : this(x.toDouble(), y.toDouble())
}

/** A local ENU offset in metres. */
data class MetreOffset(val northM: Double, val eastM: Double) {
    val magnitudeM: Double get() = hypot(northM, eastM)

    /** Compass bearing of this offset, degrees clockwise from true north. */
    val bearingDeg: Double get() = GeoMath.normalizeBearing(Math.toDegrees(atan2(eastM, northM)))
}

/**
 * The two numbers that turn bird's-eye screen pixels into metres on the ground.
 *
 * @param metresPerPixel ground metres covered by one screen pixel at the map's current zoom.
 * @param screenNorthDeg the on-screen bearing, in degrees CLOCKWISE FROM SCREEN-UP, along which
 *        true north lies. 0 means north is straight up the screen; 90 means north points to the
 *        right edge. Pikmin Bloom lets the player rotate the map, so this is not a constant.
 */
data class Calibration(val metresPerPixel: Double, val screenNorthDeg: Double) {

    private val thetaRad: Double get() = Math.toRadians(screenNorthDeg)

    /**
     * Pixel offset (from the player icon) of a point that lies [offset] on the ground.
     *
     * North on screen is the unit vector `(sin t, -cos t)` and east is 90 deg clockwise from it,
     * `(cos t, sin t)`, with `t = screenNorthDeg` and y growing downward.
     */
    fun metresToPixels(offset: MetreOffset): PixelPoint {
        val t = thetaRad
        val dx = (offset.northM * sin(t) + offset.eastM * cos(t)) / metresPerPixel
        val dy = (-offset.northM * cos(t) + offset.eastM * sin(t)) / metresPerPixel
        return PixelPoint(dx, dy)
    }

    /** Inverse of [metresToPixels]: ground offset of a point [dx], [dy] pixels from the player. */
    fun pixelsToMetres(dx: Double, dy: Double): MetreOffset {
        val t = thetaRad
        val north = metresPerPixel * (dx * sin(t) - dy * cos(t))
        val east = metresPerPixel * (dx * cos(t) + dy * sin(t))
        return MetreOffset(north, east)
    }

    fun isPlausible(): Boolean =
        metresPerPixel.isFinite() && metresPerPixel > 0.0 &&
            metresPerPixel < 100.0 && screenNorthDeg.isFinite()
}

/** One matched flower pair between two frames, plus the pixel translation it votes for. */
data class FlowerMatch(val from: FlowerHit, val to: FlowerHit) {
    val dx: Double get() = (to.anchorX - from.anchorX).toDouble()
    val dy: Double get() = (to.anchorY - from.anchorY).toDouble()
}

/** Diagnostics for a calibration attempt, so a failure can be explained rather than just `null`. */
data class CalibrationAttempt(
    val calibration: Calibration?,
    val matches: List<FlowerMatch>,
    val translationPx: PixelPoint?,
    /** RMS distance, in pixels, of the supporting matches from the agreed translation. */
    val residualPx: Double,
    val reason: String,
)

/**
 * Screen-pixel <-> world-coordinate maths for the bird's-eye map.
 *
 * ## The trick this whole feature rests on
 * The game does not publish its map scale, and the scale changes with zoom. But this app *controls
 * the simulated GPS*, so it can make its own ruler: move the simulated position a known distance,
 * take a second screenshot, and measure how far the map scrolled in pixels. Known metres over
 * measured pixels gives metres-per-pixel, and the direction of the scroll gives where north is.
 *
 * ## Caller contract - READ THIS
 * The bird's-eye camera can be **panned and rotated freely by the user**, and the player icon is
 * then no longer at the screen centre. Every function here assumes the caller knows exactly where
 * the player icon is on screen. In practice that means:
 *
 *  1. Tap the game's **recenter / "back to my position"** button before each capture, so the player
 *     icon returns to its fixed on-screen home, and pass that pixel as `playerPixel`.
 *  2. Do not zoom or rotate between the two calibration frames - a calibration is only valid for
 *     the zoom and rotation it was measured at. Re-calibrate after any pinch or twist.
 *  3. Both calibration frames must be taken in bird's-eye mode with the same camera.
 *
 * If the caller cannot guarantee the player pixel, the resulting coordinates are wrong by whatever
 * the pan offset was - silently, with no way to detect it from the image alone.
 */
object MapCalibration {

    /** Below this many mutually consistent matches, a calibration is not trustworthy. */
    const val MIN_MATCHES = 3

    /** Two matches agree if their translations differ by less than this many pixels. */
    const val TRANSLATION_TOLERANCE_PX = 14.0

    /** Maximum colour/size signature distance for a pair to be considered the same flower. */
    const val MAX_SIGNATURE_DISTANCE = 0.55

    /**
     * Derives metres-per-pixel and the screen bearing of north from two frames of the same scene,
     * taken before and after the simulated position moved by [trueMovement].
     *
     * Matching is deliberately conservative: every plausible pair (by colour + area signature)
     * proposes a translation, each translation is scored by how many *other* pairs agree with it
     * (a small RANSAC-style vote), and the winner must be supported by at least [MIN_MATCHES]
     * mutually consistent pairs. Returns `null` when that bar is not met - a wrong calibration is
     * far worse than no calibration, because it would scatter waypoints over the wrong streets.
     *
     * @param frameA detections from the first screenshot.
     * @param frameB detections from the second screenshot.
     * @param trueMovement how far the PLAYER moved between the two frames, metres north and east.
     */
    fun calibrate(
        frameA: List<FlowerHit>,
        frameB: List<FlowerHit>,
        trueMovement: MetreOffset,
    ): Calibration? = calibrateDetailed(frameA, frameB, trueMovement).calibration

    /**
     * [calibrate] with the reasoning attached, for tests and diagnostics.
     *
     * @param playerPixelA where the player icon sat in frame A. Defaults to null, meaning
     *        "the icon did not move between the frames" — true when the caller tapped recenter
     *        before each capture, which is the documented way to use this.
     * @param playerPixelB where it sat in frame B.
     *
     * The general relation, with `C` the pixel translation of the static scene and `P` the pixel
     * vector of the player's own movement:
     * ```
     * playerPixelB = playerPixelA + C + P     =>     P = (playerPixelB - playerPixelA) - C
     * ```
     * With a recentered camera `playerPixelB == playerPixelA` and this collapses to `P = -C`,
     * i.e. the map scrolls exactly opposite to the walk. With a *frozen* camera `C = 0` and the
     * icon itself does the moving. Both work; what breaks calibration is a camera that drifts
     * while the player pixel is assumed fixed, which is why recentering matters.
     */
    /**
     *  minMatches how many mutually consistent flower pairs the winning translation needs.
     *        [MIN_MATCHES] is the safe default. Callers may pass 1 or 2 for SPARSE scenes (a single
     *        Big Flower in view is common); such a result is only as good as that one match, so
     *        treat it as provisional and confirm it against a second, independent frame pair.
     */
    fun calibrateDetailed(
        frameA: List<FlowerHit>,
        frameB: List<FlowerHit>,
        trueMovement: MetreOffset,
        playerPixelA: PixelPoint? = null,
        playerPixelB: PixelPoint? = null,
        minMatches: Int = MIN_MATCHES,
    ): CalibrationAttempt {
        val need = minMatches.coerceIn(1, MIN_MATCHES)
        val distanceM = trueMovement.magnitudeM
        if (distanceM < 1e-6) {
            return CalibrationAttempt(null, emptyList(), null, 0.0, "trueMovement is zero")
        }
        if (frameA.size < need || frameB.size < need) {
            return CalibrationAttempt(
                null, emptyList(), null, 0.0,
                "too few detections: ${frameA.size} in A, ${frameB.size} in B, need $need",
            )
        }

        // Every colour-compatible pair proposes one translation. `candidateIndices` records which
        // frameA flower each candidate came from, so the vote below can cap one vote per flower.
        val candidates = ArrayList<FlowerMatch>()
        val candidateIndices = ArrayList<Int>()
        for ((ai, a) in frameA.withIndex()) {
            for (b in frameB) {
                if (a.signatureDistance(b) <= MAX_SIGNATURE_DISTANCE) {
                    candidates += FlowerMatch(a, b)
                    candidateIndices += ai
                }
            }
        }
        if (candidates.size < need) {
            return CalibrationAttempt(
                null, emptyList(), null, 0.0,
                "only ${candidates.size} colour-compatible pairs",
            )
        }

        // Vote: which proposed translation is supported by the most pairs? Each A-side flower may
        // back only ONE translation — its own closest candidate — so a flower that happens to be
        // colour-compatible with five B-side blobs cannot stuff the ballot on its own.
        // Keyed by the flower's index in frameA rather than by object identity, because FlowerHit
        // is a data class and two genuinely distinct detections can compare equal.
        var bestSupport: List<FlowerMatch> = emptyList()
        for (seed in candidates) {
            val support = HashMap<Int, FlowerMatch>()
            val supportDist = HashMap<Int, Double>()
            for ((key, c) in candidateIndices.zip(candidates)) {
                val d = hypot(c.dx - seed.dx, c.dy - seed.dy)
                if (d > TRANSLATION_TOLERANCE_PX) continue
                val held = supportDist[key]
                if (held == null || d < held) {
                    support[key] = c
                    supportDist[key] = d
                }
            }
            if (support.size > bestSupport.size) bestSupport = support.values.toList()
        }

        if (bestSupport.size < need) {
            return CalibrationAttempt(
                null, bestSupport, null, 0.0,
                "best translation only had ${bestSupport.size} consistent matches, need $need",
            )
        }

        // Refine: median translation of the supporting set (robust to one bad anchor).
        val tx = median(bestSupport.map { it.dx })
        val ty = median(bestSupport.map { it.dy })
        val residual = sqrt(bestSupport.sumOf { (it.dx - tx) * (it.dx - tx) + (it.dy - ty) * (it.dy - ty) } / bestSupport.size)

        // The world is static and the PLAYER moved. P = (playerPixelB - playerPixelA) - C, which
        // for a recentered camera (the icon does not move) is simply -C.
        val iconDx = (playerPixelB?.x ?: 0.0) - (playerPixelA?.x ?: 0.0)
        val iconDy = (playerPixelB?.y ?: 0.0) - (playerPixelA?.y ?: 0.0)
        val px = iconDx - tx
        val py = iconDy - ty
        val pixelLength = hypot(px, py)
        if (pixelLength < 1.0) {
            return CalibrationAttempt(
                null, bestSupport, PixelPoint(tx, ty), residual,
                "map barely moved (${"%.1f".format(pixelLength)} px) - movement too small for this zoom",
            )
        }

        val metresPerPixel = distanceM / pixelLength
        // Screen bearing of the movement vector, clockwise from screen-up.
        val screenBearing = GeoMath.normalizeBearing(Math.toDegrees(atan2(px, -py)))
        val screenNorthDeg = GeoMath.normalizeBearing(screenBearing - trueMovement.bearingDeg)

        val cal = Calibration(metresPerPixel, screenNorthDeg)
        return CalibrationAttempt(
            calibration = if (cal.isPlausible()) cal else null,
            matches = bestSupport,
            translationPx = PixelPoint(tx, ty),
            residualPx = residual,
            reason = if (cal.isPlausible()) {
                "ok: ${bestSupport.size} matches, residual ${"%.1f".format(residual)} px"
            } else {
                "implausible calibration $cal"
            },
        )
    }

    /**
     * World coordinate of a detected flower.
     *
     * Uses the flower's GROUND ANCHOR (stem base), not its bloom centroid - see [FlowerHit].
     *
     * @param playerPixel where the player icon sits on screen. The caller must have tapped
     *        recenter; see the class docs.
     * @param playerPos the simulated (or real) position the player icon represents.
     */
    fun toLatLng(
        hit: FlowerHit,
        playerPixel: PixelPoint,
        playerPos: LatLng,
        cal: Calibration,
    ): LatLng {
        val offset = cal.pixelsToMetres(hit.anchorX - playerPixel.x, hit.anchorY - playerPixel.y)
        return GeoMath.offsetMeters(playerPos, offset.northM, offset.eastM)
    }

    /** Straight-line ground distance, metres, between the player icon and a detection. */
    fun rangeMetres(hit: FlowerHit, playerPixel: PixelPoint, cal: Calibration): Double {
        val dx = hit.anchorX - playerPixel.x
        val dy = hit.anchorY - playerPixel.y
        return hypot(dx, dy) * cal.metresPerPixel
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** Angular difference in degrees, folded to `[0, 180]`. Handy in tests. */
    fun bearingDelta(a: Double, b: Double): Double {
        var d = abs(GeoMath.normalizeBearing(a) - GeoMath.normalizeBearing(b)) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }
}
