package app.pikminbloom.gps.feed

import app.pikminbloom.gps.vision.ColorMath
import app.pikminbloom.gps.vision.RgbImage
import kotlin.math.abs

/**
 * Recognises the feed screen and finds the blooms on it. Pure, like the rest of `vision/`: no android.*, so it
 * runs under plain JUnit. Every bloom threshold lives in [FeedTuning].
 */
object FeedVision {

    /** A bloom candidate: its sample point, and how far its brightest channel rose. */
    internal data class Bloom(val x: Int, val y: Int, val rise: Int)

    /**
     * The feed screen: the whistle at the bottom right is lit green, and the first-round button above it is green.
     * Measured on 1220x2712: feed 0.37–0.60, the other screens at most 0.15.
     */
    fun isFeedScreen(img: RgbImage): Boolean =
        greenFraction(img, 0.78, 0.95, 0.895, 0.975) >= 0.45 &&
            greenFraction(img, 0.80, 0.92, 0.655, 0.715) >= 0.30

    private fun greenFraction(img: RgbImage, x0f: Double, x1f: Double, y0f: Double, y1f: Double): Double {
        val x0 = (x0f * img.width).toInt()
        val x1 = (x1f * img.width).toInt()
        val y0 = (y0f * img.height).toInt()
        val y1 = (y1f * img.height).toInt()
        val hsv = DoubleArray(3)
        var total = 0
        var green = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                total++
                ColorMath.toHsv(img.get(x, y), hsv)
                if (hsv[0] in 120.0..170.0 && hsv[1] > 0.3 && hsv[2] > 0.25 && hsv[2] < 0.75) green++
            }
        }
        return if (total == 0) 0.0 else green.toDouble() / total
    }

    /** The grid samples over the flower field, row by row. */
    fun bloomSamples(w: Int, h: Int): List<Pair<Int, Int>> {
        val x0 = FeedTuning.GRID_X0 * w
        val x1 = FeedTuning.GRID_X1 * w
        val y0 = FeedTuning.GRID_Y0 * h
        val y1 = FeedTuning.GRID_Y1 * h
        val colSpan = FeedTuning.GRID_COLS - 1
        val rowSpan = FeedTuning.GRID_ROWS - 1
        return (0 until FeedTuning.GRID_ROWS).flatMap { j ->
            (0 until FeedTuning.GRID_COLS).map { i ->
                (x0 + i * (x1 - x0) / colSpan).toInt() to (y0 + j * (y1 - y0) / rowSpan).toInt()
            }
        }
    }

    /**
     * The bloom candidates between the feed frame before feeding ([before]) and after ([after]), strongest first.
     * A sample is a candidate when its brightest channel rose by [FeedTuning.BLOOM_RISE], it is bright, and it is
     * warm or white. Empty when the frame moved: more than [FeedTuning.CAMERA_MOVE_FRACTION] of the samples changed.
     */
    fun findBlooms(before: RgbImage, after: RgbImage): List<Pair<Int, Int>> {
        if (before.width != after.width || before.height != after.height) return emptyList()
        val w = after.width
        val h = after.height
        val samples = bloomSamples(w, h)
        val hsv = DoubleArray(3)
        var changed = 0
        val candidates = ArrayList<Bloom>()
        for ((x, y) in samples) {
            val rise = maxChannel(after.get(x, y)) - maxChannel(before.get(x, y))
            if (abs(rise) >= FeedTuning.CHANGED_BY) changed++
            if (rise < FeedTuning.BLOOM_RISE) continue
            ColorMath.toHsv(after.get(x, y), hsv)
            val bright = hsv[2] >= FeedTuning.BRIGHT_V
            val warm = hsv[0] in FeedTuning.WARM_HUE_MIN..FeedTuning.WARM_HUE_MAX && hsv[1] >= FeedTuning.WARM_SAT
            val white = hsv[1] < FeedTuning.WHITE_SAT
            if (bright && (warm || white)) candidates.add(Bloom(x, y, rise))
        }
        if (changed > FeedTuning.CAMERA_MOVE_FRACTION * samples.size) return emptyList()
        return dedupe(candidates.sortedByDescending { it.rise }, w, h).map { it.x to it.y }
    }

    /** Greedy, strongest first: a candidate inside the box of a kept one is the same bloom. */
    internal fun dedupe(sorted: List<Bloom>, w: Int, h: Int): List<Bloom> {
        val boxX = FeedTuning.DEDUPE_W * w
        val boxY = FeedTuning.DEDUPE_H * h
        val kept = ArrayList<Bloom>()
        for (c in sorted) {
            if (kept.none { abs(it.x - c.x) <= boxX && abs(it.y - c.y) <= boxY }) kept.add(c)
        }
        return kept
    }

    private fun maxChannel(p: Int): Int = maxOf((p ushr 16) and 0xFF, (p ushr 8) and 0xFF, p and 0xFF)
}
