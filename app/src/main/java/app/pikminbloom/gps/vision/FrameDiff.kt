package app.pikminbloom.gps.vision

import kotlin.math.abs
import kotlin.math.max

/** Inclusive pixel rectangle. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1

    fun clampTo(w: Int, h: Int): Box =
        Box(left.coerceIn(0, w - 1), top.coerceIn(0, h - 1), right.coerceIn(0, w - 1), bottom.coerceIn(0, h - 1))

    companion object {
        fun around(cx: Int, cy: Int, halfW: Int, halfH: Int) = Box(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }
}

/**
 * Before/after comparison of two capture frames. The nectar collector has no templates of the
 * game's UI; every step is verified by "did something appear where I expect it" (PLAN E5), and
 * these are the two questions it asks. Sampled on a grid, so a full-screen call is cheap.
 */
object FrameDiff {
    /** Per-channel delta a pixel must exceed to count as changed; below this is compression/lighting jitter. */
    const val DEFAULT_THRESHOLD = 40
    const val DEFAULT_STEP = 4

    private fun changed(a: Int, b: Int, threshold: Int): Boolean {
        val dr = abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF))
        val dg = abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db)) > threshold
    }

    /** Fraction [0,1] of sampled pixels that differ. Frames of different sizes count as fully changed. */
    fun changedFraction(a: RgbImage, b: RgbImage, threshold: Int = DEFAULT_THRESHOLD, step: Int = DEFAULT_STEP): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        var total = 0
        var diff = 0
        var y = step / 2
        while (y < a.height) {
            var x = step / 2
            while (x < a.width) {
                total++
                if (changed(a.get(x, y), b.get(x, y), threshold)) diff++
                x += step
            }
            y += step
        }
        return if (total == 0) 0.0 else diff.toDouble() / total
    }

    /**
     * Fraction of sampled pixels in [region] that are bright and unsaturated (white / near-white
     * UI panels). The game's info page is a white bottom sheet; a meadow, sky or map is not.
     */
    fun brightFraction(img: RgbImage, region: Box, minValue: Int = 200, maxChroma: Int = 40, step: Int = DEFAULT_STEP): Double {
        val r = region.clampTo(img.width, img.height)
        var total = 0
        var bright = 0
        var y = r.top
        while (y <= r.bottom) {
            var x = r.left
            while (x <= r.right) {
                val p = img.get(x, y)
                val red = (p ushr 16) and 0xFF; val g = (p ushr 8) and 0xFF; val b = p and 0xFF
                val hi = max(red, max(g, b)); val lo = minOf(red, g, b)
                total++
                if (hi >= minValue && hi - lo <= maxChroma) bright++
                x += step
            }
            y += step
        }
        return if (total == 0) 0.0 else bright.toDouble() / total
    }

    /**
     * Bounding box of the pixels inside [region] that differ between [a] and [b], or null when fewer
     * than [minSamples] sampled pixels changed (a speck of animation is not a label).
     */
    fun changedBox(
        a: RgbImage, b: RgbImage, region: Box,
        threshold: Int = DEFAULT_THRESHOLD, step: Int = DEFAULT_STEP, minSamples: Int = 12,
    ): Box? {
        if (a.width != b.width || a.height != b.height) return null
        val r = region.clampTo(a.width, a.height)
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var rr = -1; var bb = -1
        var n = 0
        var y = r.top
        while (y <= r.bottom) {
            var x = r.left
            while (x <= r.right) {
                if (changed(a.get(x, y), b.get(x, y), threshold)) {
                    n++
                    if (x < l) l = x; if (x > rr) rr = x
                    if (y < t) t = y; if (y > bb) bb = y
                }
                x += step
            }
            y += step
        }
        return if (n < minSamples) null else Box(l, t, rr, bb)
    }
}
