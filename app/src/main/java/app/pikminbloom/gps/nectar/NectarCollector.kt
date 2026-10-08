package app.pikminbloom.gps.nectar

import app.pikminbloom.gps.vision.Box
import app.pikminbloom.gps.vision.FlowerHit
import app.pikminbloom.gps.vision.FrameDiff
import app.pikminbloom.gps.vision.PixelPoint
import app.pikminbloom.gps.vision.RgbImage
import kotlin.math.hypot

/** What the collector can do to the phone. The Android implementation is NectarAccessibilityService + ScreenCaptureService. */
interface NectarIo {
    suspend fun frame(): RgbImage?
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun swipe(x: Int, fromY: Int, toY: Int, durationMs: Long): Boolean
    suspend fun back(): Boolean
    suspend fun wait(ms: Long)
}

enum class NectarStage { FIND_FLOWER, TAP_FLOWER, TAP_LABEL, SWIPE, CLOSE }

sealed class NectarResult {
    data object Collected : NectarResult()
    data class Failed(val stage: NectarStage, val reason: String) : NectarResult()
}

/**
 * 自動拉花 in bird's-eye view (PLAN E5): tap the Big Flower → its name label pops up → tap the
 * label → the info page opens → swipe down on it → nectar. There are no templates of the game's
 * UI here; each step is verified as "something appeared where it should" with [FrameDiff], and
 * the sequence stops at the first step that shows nothing, so it never taps blindly.
 *
 * What it cannot verify is the nectar itself: the swipe either gave nectar or the flower was not
 * blooming / already collected, and both look the same to us. [NectarResult.Collected] means
 * "the info page was reached and swiped", nothing more.
 */
class NectarCollector(
    private val io: NectarIo,
    private val detect: (RgbImage) -> List<FlowerHit>,
    private val log: (String) -> Unit,
) {
    suspend fun collect(avatar: PixelPoint, maxDistancePx: Int = DEFAULT_MAX_DISTANCE_PX): NectarResult {
        val frame0 = io.frame() ?: return fail(NectarStage.FIND_FLOWER, "no frame")
        // Not too close: the avatar's own marker and its pikmin are flower-coloured blobs right at
        // the avatar point, and tapping the avatar in bird's-eye view flips back to the walking
        // view (seen on device). A Big Flower we walked to sits at the circle edge, further out.
        val flower = detect(frame0)
            .filter { hypot(it.centroidX - avatar.x, it.centroidY - avatar.y) in MIN_DISTANCE_PX.toDouble()..maxDistancePx.toDouble() }
            .sortedWith(compareByDescending<FlowerHit> { it.stemFound }.thenBy { hypot(it.centroidX - avatar.x, it.centroidY - avatar.y) })
            .firstOrNull()
            ?: return fail(NectarStage.FIND_FLOWER, "no flower near the avatar")
        log("flower at (${flower.centroidX},${flower.centroidY}), anchor (${flower.anchorX},${flower.anchorY})")

        // 1. Tap the flower; a name label should appear next to it. Retry once on the centroid,
        //    the anchor (stem base) is sometimes just below the tappable art.
        var label: Box? = null
        var before = frame0
        for ((x, y) in listOf(flower.anchorX to flower.anchorY, flower.centroidX to flower.centroidY)) {
            io.tap(x, y)
            io.wait(SETTLE_MS)
            val after = io.frame() ?: return fail(NectarStage.TAP_FLOWER, "no frame after tap")
            val region = Box.around(flower.centroidX, flower.centroidY, LABEL_SEARCH_HALF_W, LABEL_SEARCH_HALF_H)
            // A label (measured: ~290x110 px, centred ~100 px straight above the head) is a bounded
            // change above the flower; the whole region changing means the map moved or the view flipped.
            label = FrameDiff.changedBox(before, after, region)?.takeIf {
                it.width in MIN_LABEL_W..MAX_LABEL_W && it.height in MIN_LABEL_H..MAX_LABEL_H && it.centerY < flower.centroidY
            }
            before = after
            if (label != null) break
        }
        val lbl = label ?: return fail(NectarStage.TAP_FLOWER, "no label appeared")
        log("label ${lbl.width}x${lbl.height} at (${lbl.centerX},${lbl.centerY})")

        // 2. Tap the label; the info page should cover most of the screen.
        io.tap(lbl.centerX, lbl.centerY)
        io.wait(PAGE_MS)
        val page = io.frame() ?: return fail(NectarStage.TAP_LABEL, "no frame after label tap")
        val pageChange = FrameDiff.changedFraction(before, page)
        // The info page is a white bottom sheet over a photo; a switch to the lobby / walking view
        // also repaints everything but its bottom is a meadow, so the sheet is the real test.
        val sheet = FrameDiff.brightFraction(page, Box(0, (page.height * SHEET_TOP).toInt(), page.width - 1, page.height - 1))
        if (pageChange < MIN_PAGE_FRACTION || sheet < MIN_SHEET_FRACTION) {
            return fail(NectarStage.TAP_LABEL, "no info page (%.0f%% changed, sheet %.0f%%)".format(pageChange * 100, sheet * 100))
        }
        log("info page (%.0f%% of the screen changed, sheet %.0f%% white)".format(pageChange * 100, sheet * 100))

        // 3. Swipe down on the page: that is the nectar gesture.
        io.swipe(page.width / 2, (page.height * SWIPE_FROM).toInt(), (page.height * SWIPE_TO).toInt(), SWIPE_MS)
        io.wait(PAGE_MS)
        val swiped = io.frame() ?: page

        // 4. Close the page (back, twice if needed) and make sure the map is back.
        for (attempt in 1..2) {
            io.back()
            io.wait(SETTLE_MS)
            val now = io.frame() ?: continue
            if (FrameDiff.changedFraction(swiped, now) >= MIN_PAGE_FRACTION) {
                log("page closed after $attempt back")
                return NectarResult.Collected
            }
        }
        return fail(NectarStage.CLOSE, "info page still open")
    }

    private fun fail(stage: NectarStage, reason: String): NectarResult.Failed {
        log("failed at $stage: $reason")
        return NectarResult.Failed(stage, reason)
    }

    companion object {
        /** A flower we walked to is at the circle edge, ~30 m = ~50 px at 0.57 m/px; be generous. */
        const val DEFAULT_MAX_DISTANCE_PX = 350
        /** Closer than this is the avatar marker itself (~35 px radius on a 1220 px wide screen). */
        const val MIN_DISTANCE_PX = 40
        const val LABEL_SEARCH_HALF_W = 300
        const val LABEL_SEARCH_HALF_H = 350
        const val MIN_LABEL_W = 60
        const val MIN_LABEL_H = 24
        const val MAX_LABEL_W = 500
        const val MAX_LABEL_H = 220
        /** The info page's white sheet starts a bit past mid-screen (measured 57%); test the lower 40%. */
        const val SHEET_TOP = 0.60
        const val MIN_SHEET_FRACTION = 0.5
        /** Fraction of the screen an info page must repaint. */
        const val MIN_PAGE_FRACTION = 0.35
        const val SWIPE_FROM = 0.45
        const val SWIPE_TO = 0.80
        const val SWIPE_MS = 400L
        const val SETTLE_MS = 900L
        const val PAGE_MS = 1500L
    }
}
