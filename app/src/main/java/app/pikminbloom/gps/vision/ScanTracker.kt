package app.pikminbloom.gps.vision

import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.vision.FlowerScanPlan.PlannedFlower
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Every decision the bird's-eye scan makes, with no Android in it.
 *
 * `FlowerScanner` (the Android side) captures a frame, runs [FlowerDetector], reads the simulated
 * position, and hands all three to [onFrame]; this class answers "what does that frame mean" and
 * keeps the running result. Splitting it this way is what makes the scan testable: a JUnit test can
 * replay synthetic detections with known positions and assert exactly when a calibration is
 * attempted, accepted, replaced or refused.
 *
 * ## How a session goes
 *  1. Frames with more than [WALK_VIEW_HIT_THRESHOLD] detections are the 3-D walk view (measured:
 *     60-80 detections there versus at most 6 in bird's-eye) and are ignored with [Outcome.WrongView].
 *  2. While no calibration exists, a **stationary** frame is taken every [STATIONARY_SPACING_M]
 *     of walking ([wantsStationaryFrame] tells the scanner when to pause the walker for one) — the
 *     patrol does the walking by itself.
 *  3. Every new stationary frame is compared with each stored one at least [MIN_CALIBRATION_MOVE_M]
 *     away by [MapCalibration.calibrateDetailed], against the known movement. Success gives
 *     metres-per-pixel and the screen bearing of north; failure just waits for the next frame.
 *  4. Once calibrated every usable frame is planned with [FlowerScanPlan] and merged into [found].
 *     A fresh calibration is attempted again every [RECALIBRATE_EVERY_M] and only replaces the
 *     current one when its residual is lower.
 *
 * ## Why the calibration frames must be stationary (measured live, 2026-09-11)
 * The game draws its avatar several seconds behind the mock fixes. Two frames taken mid-walk
 * therefore disagree with the simulated displacement by however far the walker went in those
 * seconds, and on a short back-and-forth patrol the sign even flips: a live attempt from moving
 * frames measured 0.44 m/px with north pointing *down*, against 0.31 m/px / north-up from the
 * paused capture pair. Pausing lets the avatar catch up, and the position is then exact.
 * Moving frames are still scanned for flowers, but their camera position is read off the scene
 * ([displayedPosition]) rather than trusted from the simulator.
 *
 * A [savedCalibration] from an earlier session is a shortcut, not a fact: it is only adopted once a
 * short-baseline ([SAVED_VALIDATION_MOVE_M]) measurement agrees with it, because the map zoom may
 * have changed since it was saved, and a wrong scale scatters waypoints over the wrong streets.
 */
class ScanTracker(
    savedCalibration: Calibration? = null,
    private val options: FlowerScanPlan.Options = DEFAULT_OPTIONS,
) {

    /** What one frame meant. */
    sealed class Outcome {
        /** Too many detections: this is the walk view, not bird's-eye. */
        data object WrongView : Outcome()

        /**
         * The frame is (almost) entirely black. Pikmin Bloom flips its surface to secure — black in
         * every capture — when it notices a capture display appearing while it runs; it does not
         * when it is launched *after* the projection already exists. [streak] consecutive such frames.
         */
        data class Blank(val streak: Int) : Outcome() {
            val exhausted: Boolean get() = streak >= BLANK_STREAK_LIMIT
        }

        /** No detections; [streak] consecutive such frames so far. */
        data class Empty(val streak: Int) : Outcome() {
            val exhausted: Boolean get() = streak >= EMPTY_STREAK_LIMIT
        }

        /** Usable frame, no calibration yet. [metresSoFar] is the walk since the reference frame. */
        data class Calibrating(val usableFrames: Int, val metresSoFar: Double) : Outcome()

        /** Usable frame under a calibration; [newlyFound] were added to [found] by this frame. */
        data class Scanning(
            val calibration: Calibration,
            val found: List<PlannedFlower>,
            val newlyFound: List<PlannedFlower>,
            /** Non-null when this frame produced (or replaced) the calibration. */
            val calibrationChanged: CalibrationAttempt?,
            /** True when the calibration in force was adopted from the saved one. */
            val adoptedSaved: Boolean,
            /** The camera position the flowers were planned from (scene-derived for moving frames). */
            val planPos: LatLng,
        ) : Outcome()
    }

    /**
     * [hits] feed the scan plan; [landmarks] (hits + mushroom badges) feed the calibration and the
     * scene-translation tracking. [stationary] frames were captured with the patrol paused long
     * enough for the game's smoothed avatar to settle on the exact simulated position.
     */
    private class Frame(
        val hits: List<FlowerHit>,
        val landmarks: List<FlowerHit>,
        val pos: LatLng,
        val playerPixel: PixelPoint,
        val stationary: Boolean,
    )

    /**
     * Recent STATIONARY frames, oldest first, spaced at least [STATIONARY_SPACING_M] apart along
     * the walk. Any two of them [MIN_CALIBRATION_MOVE_M] apart make a calibration baseline, so a
     * patrol that shuttles back and forth over a short leg still calibrates: it does not matter
     * where the first frame happened to be taken, only that two frames end up far enough apart.
     */
    private val stationaryFrames = ArrayDeque<Frame>()
    private var emptyStreak = 0
    private var blankStreak = 0
    private var usableFrames = 0
    private var savedPending: Calibration? = savedCalibration?.takeIf { it.isPlausible() }

    /** The calibration in force, if any. */
    var calibration: Calibration? = null
        private set

    /** The measurement behind [calibration]; the residual is what a replacement must beat. */
    var calibrationAttempt: CalibrationAttempt? = null
        private set

    private var calibratedAt: LatLng? = null
    private var calibrationFromSaved = false

    /** Everything found so far, deduplicated, nearest detection wins. */
    var found: List<PlannedFlower> = emptyList()
        private set

    /** True once at least one stationary frame has been taken. */
    val hasReference: Boolean get() = stationaryFrames.isNotEmpty()

    /**
     * Whether the scanner should stop the walker and take a stationary frame now: while a
     * calibration is needed (none yet, or the last one is [RECALIBRATE_EVERY_M] behind), one
     * every [STATIONARY_SPACING_M] of walking. Cheap; call it every frame.
     */
    fun wantsStationaryFrame(pos: LatLng): Boolean {
        if (stationaryFrames.isEmpty()) return true
        val since = calibratedAt?.let { GeoMath.distanceM(it, pos) } ?: Double.MAX_VALUE
        if (!shouldAttemptCalibration(calibration != null, since)) return false
        return stationaryFrames.none { GeoMath.distanceM(it.pos, pos) < STATIONARY_SPACING_M }
    }

    /** Longest baseline available from the stored stationary frames to [pos]; the calibration progress. */
    private fun baselineM(pos: LatLng): Double =
        stationaryFrames.maxOfOrNull { GeoMath.distanceM(it.pos, pos) } ?: 0.0

    /**
     * @param blank true when the frame is black (see [isMostlyBlack]); the caller measures it on the
     *        raw image because [DetectionResult] cannot tell "black" from "grass with no flowers".
     * @param stationary true when the walker was paused for long enough before this capture.
     */
    fun onFrame(
        result: DetectionResult,
        width: Int,
        height: Int,
        pos: LatLng,
        blank: Boolean = false,
        stationary: Boolean = false,
    ): Outcome {
        if (blank) {
            blankStreak++
            return Outcome.Blank(blankStreak)
        }
        blankStreak = 0
        when (classifyFrame(result.hits.size)) {
            FrameVerdict.WALK_VIEW -> {
                // The camera is different in the walk view; whatever references we held are stale.
                stationaryFrames.clear()
                emptyStreak = 0
                return Outcome.WrongView
            }
            FrameVerdict.EMPTY -> {
                emptyStreak++
                return Outcome.Empty(emptyStreak)
            }
            FrameVerdict.USABLE -> Unit
        }
        emptyStreak = 0
        usableFrames++

        val frame = Frame(result.hits, calibrationLandmarks(result), pos, playerPixel(result.avatars, width, height), stationary)
        // Reference frames no longer require the walker to stop. The avatar's easing lags the
        // simulated position by a couple of metres at walking pace, which over a 40 m baseline
        // is a few percent of scale - well inside the error budget - whereas pausing the patrol
        // every 20 m made the game character visibly stop-start for the whole scan.
        val asReference = stationary || wantsStationaryFrame(pos)
        val change = if (asReference) onStationaryFrame(frame) else null
        val cal = calibration ?: return Outcome.Calibrating(usableFrames, baselineM(pos))

        // A moving frame's avatar lags the simulated position; where the camera *actually* is can
        // be read off the scene instead, as its pixel translation from a stationary frame.
        val planPos = if (stationary) frame.pos else displayedPosition(frame, cal) ?: frame.pos
        val plan = FlowerScanPlan.build(frame.hits, frame.playerPixel, planPos, cal, options)
        val before = found
        found = mergeFound(before, plan, options)
        val newly = found.drop(before.size)
        return Outcome.Scanning(cal, found, newly, change, calibrationFromSaved, planPos)
    }

    /**
     * A stationary frame is matched against every stored one far enough away (farthest first) and,
     * whether or not that produced a calibration, joins the store.
     */
    private fun onStationaryFrame(frame: Frame): CalibrationAttempt? {
        var change: CalibrationAttempt? = null
        val since = calibratedAt?.let { GeoMath.distanceM(it, frame.pos) } ?: Double.MAX_VALUE
        if (shouldAttemptCalibration(calibration != null, since)) {
            val byDistance = stationaryFrames
                .map { it to GeoMath.distanceM(it.pos, frame.pos) }
                .sortedByDescending { it.second }

            for ((other, d) in byDistance) {
                if (d < MIN_CALIBRATION_MOVE_M) break
                val attempt = calibrate(other, frame)
                if (attempt.calibration != null && confirmed(attempt) && isBetterCalibration(calibrationAttempt, attempt)) {
                    adopt(attempt.calibration!!, attempt, frame.pos, fromSaved = false)
                    provisional = null
                    change = attempt
                    break
                }
                // A valid measurement that is not better than the current one ends the search;
                // a failed match (no shared landmarks) just tries the next stored frame.
                if (attempt.calibration != null && attempt.matches.size >= MapCalibration.MIN_MATCHES) break
            }

            // Saved-calibration fast path: a short baseline is enough to *check* a scale, just not
            // to measure one from scratch.
            val saved = savedPending
            if (change == null && calibration == null && saved != null) {
                for ((other, d) in byDistance) {
                    if (d < SAVED_VALIDATION_MOVE_M) break
                    if (d >= MIN_CALIBRATION_MOVE_M) continue   // handled above, and it failed
                    val attempt = calibrate(other, frame)
                    val fresh = attempt.calibration ?: continue
                    savedPending = null
                    if (savedCalibrationAgrees(saved, fresh)) {
                        adopt(saved, attempt, frame.pos, fromSaved = true)
                        change = attempt
                    }
                    // Disagrees: the zoom changed; the full measurement takes over at 40 m.
                    break
                }
            }
        }
        stationaryFrames.addLast(frame)
        while (stationaryFrames.size > STATIONARY_HISTORY) stationaryFrames.removeFirst()
        return change
    }

    /**
     * A calibration measured from fewer than [MapCalibration.MIN_MATCHES] flowers, waiting for a
     * second independent measurement to agree with it. One Big Flower in view is the common case
     * in a residential area, and refusing to calibrate at all there made the feature useless;
     * requiring two sparse measurements to agree is what keeps a single wrong pairing from
     * scattering waypoints over the wrong streets.
     */
    private var provisional: CalibrationAttempt? = null

    private fun calibrate(a: Frame, b: Frame): CalibrationAttempt {
        val sparse = minOf(a.landmarks.size, b.landmarks.size) < MapCalibration.MIN_MATCHES
        return MapCalibration.calibrateDetailed(
            a.landmarks, b.landmarks, movement(a.pos, b.pos), a.playerPixel, b.playerPixel,
            minMatches = if (sparse) 1 else MapCalibration.MIN_MATCHES,
        )
    }

    /**
     * Whether [attempt] may be adopted right now. Full measurements ([MapCalibration.MIN_MATCHES]+
     * consistent flowers) are trusted directly; sparse ones only once a second sparse measurement
     * from a different frame pair agrees on scale and north.
     */
    private fun confirmed(attempt: CalibrationAttempt): Boolean {
        val cal = attempt.calibration ?: return false
        if (attempt.matches.size >= MapCalibration.MIN_MATCHES) return true
        val prev = provisional?.calibration
        if (prev != null && sparseAgrees(prev, cal)) return true
        provisional = attempt
        return false
    }

    private fun adopt(cal: Calibration, attempt: CalibrationAttempt, at: LatLng, fromSaved: Boolean) {
        calibration = cal
        calibrationAttempt = attempt
        calibratedAt = at
        calibrationFromSaved = fromSaved
        if (!fromSaved) savedPending = null
    }

    /**
     * Where the camera is for a moving frame, derived from the scene's pixel translation since
     * the nearest stationary frame (whose position is exact). Null when too few landmarks match.
     */
    private fun displayedPosition(frame: Frame, cal: Calibration): LatLng? {
        val ref = stationaryFrames.minByOrNull { GeoMath.distanceM(it.pos, frame.pos) } ?: return null
        val movement = movement(ref.pos, frame.pos)
        if (movement.magnitudeM < 1e-3) return null
        val attempt = MapCalibration.calibrateDetailed(ref.landmarks, frame.landmarks, movement, ref.playerPixel, frame.playerPixel)
        val t = attempt.translationPx ?: return null
        // playerPixelB = playerPixelA + C + P  =>  P = (playerPixelB - playerPixelA) - C
        val px = (frame.playerPixel.x - ref.playerPixel.x) - t.x
        val py = (frame.playerPixel.y - ref.playerPixel.y) - t.y
        val off = cal.pixelsToMetres(px, py)
        return GeoMath.offsetMeters(ref.pos, off.northM, off.eastM)
    }

    companion object {
        /** Walk view produces 60-80 detections; bird's-eye at most ~6. Above this = wrong view. */
        const val WALK_VIEW_HIT_THRESHOLD = 15

        /** Consecutive empty frames before the user is told to move the map. */
        const val EMPTY_STREAK_LIMIT = 5

        /** Consecutive black frames before the user is told the game has blanked itself. */
        const val BLANK_STREAK_LIMIT = 3

        /**
         * A frame whose sampled pixels are at least this dark, this often, is "black". 90 % rather
         * than ~100 % because the floating bar, the status bar and the notch sit on top of the
         * blanked game and stay visible; a real bird's-eye frame has no near-black at all.
         */
        const val BLANK_MAX_VALUE = 0.06
        const val BLANK_MIN_FRACTION = 0.90

        /** Minimum walk between the two calibration frames (129 px at the measured 0.31 m/px). */
        const val MIN_CALIBRATION_MOVE_M = 40.0

        /** While calibrating, take a stationary frame every this many metres of walking. */
        const val STATIONARY_SPACING_M = 20.0

        /** How many stationary frames to keep for pairing (8 x 20 m covers a 160 m stretch). */
        const val STATIONARY_HISTORY = 8

        /** Re-measure the calibration after this much walking; adopt it only if the residual is lower. */
        const val RECALIBRATE_EVERY_M = 300.0

        /** Detections further away than this from the player are dropped (accuracy falls off with range). */
        const val MAX_RANGE_M = 400.0

        /** Two detections closer than this are the same Big Flower. */
        const val DEDUPE_RADIUS_M = 20.0

        /** Walk needed before a saved calibration is checked against a fresh short-baseline one. */
        const val SAVED_VALIDATION_MOVE_M = 12.0

        /** A saved scale within this fraction of the fresh one is "the same zoom". */
        const val SAVED_SCALE_TOLERANCE = 0.25

        /** ...and north within this many degrees is "the same rotation". */
        const val SAVED_NORTH_TOLERANCE_DEG = 20.0

        /** Saved calibrations older than this are ignored outright. */
        const val SAVED_MAX_AGE_MS = 24L * 60L * 60L * 1000L

        /**
         * Where the player pill sits after the game's recenter button, as a fraction of the frame:
         * (610, 1353) on the 1220x2712 reference screen. Used when no avatar blob is detected.
         */
        const val DEFAULT_PLAYER_X_FRAC = 0.5
        const val DEFAULT_PLAYER_Y_FRAC = 0.499

        /** An avatar blob further than this fraction of the width from the default spot is not ours. */
        const val MAX_AVATAR_OFFSET_FRAC = 0.45

        val DEFAULT_OPTIONS = FlowerScanPlan.Options(
            dedupeRadiusM = DEDUPE_RADIUS_M,
            maxRadiusM = MAX_RANGE_M,
        )

        enum class FrameVerdict { WALK_VIEW, EMPTY, USABLE }

        fun classifyFrame(hitCount: Int): FrameVerdict = when {
            hitCount > WALK_VIEW_HIT_THRESHOLD -> FrameVerdict.WALK_VIEW
            hitCount == 0 -> FrameVerdict.EMPTY
            else -> FrameVerdict.USABLE
        }

        /**
         * Cheap black-frame test on a sparse grid (about 1/1000 of the pixels). A secure surface
         * captures as pure black; a real bird's-eye frame is mostly grass (V ≈ 0.7).
         */
        fun isMostlyBlack(image: RgbImage, step: Int = 32): Boolean {
            var dark = 0
            var total = 0
            var y = step / 2
            while (y < image.height) {
                var x = step / 2
                while (x < image.width) {
                    if (ColorMath.value(image.get(x, y)) <= BLANK_MAX_VALUE) dark++
                    total++
                    x += step
                }
                y += step
            }
            return total > 0 && dark.toDouble() / total >= BLANK_MIN_FRACTION
        }

        /**
         * What the calibration matches between two frames: the flower hits plus every mushroom
         * "person count" badge. Badges are never waypoints, but they are the most reliably detected
         * thing on the map (dark teal pill, fixed size, found in every frame of the capture set),
         * and a scene with only two Big Flowers cannot otherwise reach [MapCalibration.MIN_MATCHES].
         * The badge's own colour signature keeps it from being matched to a flower.
         */
        fun calibrationLandmarks(result: DetectionResult): List<FlowerHit> =
            result.hits + result.badges.map { b ->
                FlowerHit(
                    centroidX = b.centroidX.toInt(), centroidY = b.centroidY.toInt(),
                    anchorX = b.centroidX.toInt(), anchorY = b.centroidY.toInt(),
                    areaPx = b.areaPx,
                    left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                    hueDeg = b.meanHueDeg, sat = b.meanSat, value = b.meanVal,
                    stemFound = false,
                )
            }

        /**
         * The player's on-screen pixel: the detected avatar pill nearest to the recentered home
         * spot, or that spot itself. The user is asked to tap the game's recenter button before a
         * scan; the avatar search is what tolerates a small pan anyway.
         */
        fun playerPixel(avatars: List<Blob>, width: Int, height: Int): PixelPoint {
            val fallback = PixelPoint(width * DEFAULT_PLAYER_X_FRAC, height * DEFAULT_PLAYER_Y_FRAC)
            val best = avatars.minByOrNull { hypot(it.centroidX - fallback.x, it.centroidY - fallback.y) }
                ?: return fallback
            val d = hypot(best.centroidX - fallback.x, best.centroidY - fallback.y)
            return if (d <= width * MAX_AVATAR_OFFSET_FRAC) PixelPoint(best.centroidX, best.centroidY) else fallback
        }

        /** Ground movement [from] -> [to] as north / east metres, the frame [MapCalibration] wants. */
        fun movement(from: LatLng, to: LatLng): MetreOffset {
            val d = GeoMath.distanceM(from, to)
            if (d < 1e-9) return MetreOffset(0.0, 0.0)
            val b = Math.toRadians(GeoMath.bearingDeg(from, to))
            return MetreOffset(northM = d * cos(b), eastM = d * sin(b))
        }

        /** Measure when there is no calibration, or the last one is [RECALIBRATE_EVERY_M] behind us. */
        fun shouldAttemptCalibration(hasCalibration: Boolean, metresSinceCalibration: Double): Boolean =
            !hasCalibration || metresSinceCalibration >= RECALIBRATE_EVERY_M

        /** A candidate replaces the current calibration only when it is plausible and fits better. */
        fun isBetterCalibration(current: CalibrationAttempt?, candidate: CalibrationAttempt): Boolean {
            val cal = candidate.calibration ?: return false
            if (!cal.isPlausible()) return false
            if (current?.calibration == null) return true
            return candidate.residualPx < current.residualPx
        }

        /** Same zoom and rotation, within tolerance. */
        /** Two sparse (1-2 flower) measurements support each other if scale and north line up. */
        fun sparseAgrees(a: Calibration, b: Calibration): Boolean {
            if (!a.isPlausible() || !b.isPlausible()) return false
            val ratio = a.metresPerPixel / b.metresPerPixel
            if (ratio < 1.0 - SPARSE_SCALE_TOLERANCE || ratio > 1.0 + SPARSE_SCALE_TOLERANCE) return false
            return MapCalibration.bearingDelta(a.screenNorthDeg, b.screenNorthDeg) <= SPARSE_NORTH_TOLERANCE_DEG
        }

        /** Tolerances for [sparseAgrees]: two honest one-flower measurements land well inside these. */
        const val SPARSE_SCALE_TOLERANCE = 0.20
        const val SPARSE_NORTH_TOLERANCE_DEG = 15.0

        fun savedCalibrationAgrees(saved: Calibration, fresh: Calibration): Boolean {
            if (!saved.isPlausible() || !fresh.isPlausible()) return false
            val ratio = saved.metresPerPixel / fresh.metresPerPixel
            if (ratio < 1.0 - SAVED_SCALE_TOLERANCE || ratio > 1.0 + SAVED_SCALE_TOLERANCE) return false
            return MapCalibration.bearingDelta(saved.screenNorthDeg, fresh.screenNorthDeg) <= SAVED_NORTH_TOLERANCE_DEG
        }

        /** Whether a persisted calibration is even worth validating. */
        fun isSavedUsable(saved: Calibration?, savedAtMs: Long, nowMs: Long): Boolean {
            if (saved == null || !saved.isPlausible()) return false
            if (savedAtMs <= 0L) return false
            val age = nowMs - savedAtMs
            return age in 0..SAVED_MAX_AGE_MS
        }

        /**
         * Merges one frame's plan into the running list. Existing entries keep their number and
         * name; a repeat sighting within the dedupe radius only refines the entry — the nearer
         * detection's coordinate wins, because accuracy falls off with distance from the camera.
         */
        fun mergeFound(
            existing: List<PlannedFlower>,
            plan: FlowerScanPlan.Plan,
            options: FlowerScanPlan.Options = DEFAULT_OPTIONS,
        ): List<PlannedFlower> {
            val out = existing.toMutableList()
            for (f in plan.flowers) {
                val i = out.indexOfFirst {
                    GeoMath.distanceM(it.waypoint.latLng, f.waypoint.latLng) <= options.dedupeRadiusM
                }
                if (i < 0) {
                    val n = out.size + 1
                    out += f.copy(
                        waypoint = f.waypoint.copy(
                            id = "${options.idPrefix}-$n-${f.waypoint.id.substringAfterLast('-')}",
                            name = "${options.namePrefix}$n",
                        ),
                    )
                } else {
                    val cur = out[i]
                    val nearer = f.rangeM < cur.rangeM
                    out[i] = cur.copy(
                        waypoint = if (nearer) cur.waypoint.copy(lat = f.waypoint.lat, lon = f.waypoint.lon) else cur.waypoint,
                        rangeM = min(cur.rangeM, f.rangeM),
                        mergedCount = cur.mergedCount + max(1, f.mergedCount),
                        stemFound = cur.stemFound || f.stemFound,
                    )
                }
            }
            return out
        }
    }
}
