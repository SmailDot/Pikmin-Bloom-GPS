package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.vision.ColorMath
import app.pikminbloom.gps.vision.RgbImage
import kotlin.math.abs
import kotlin.math.hypot

enum class ExpScreen { LIST, DETAIL, SELECT, RESULT, OTHER }

/** What a list cell holds. The session only ever taps POT or FRUIT. */
enum class ItemKind { POT, FRUIT, GIFT, UNKNOWN, COVERED, IN_PROGRESS }

/** A GO bubble with at least this saturated fraction of its pixels counts as active (picked 自動). */
const val GO_ACTIVE_FRACTION = 0.08

/** A list cell: [x] is the column centre, [y] the centre of its icon. */
data class Cell(val x: Int, val y: Int, val kind: ItemKind)

/**
 * What one captured frame shows. [cells] and [tabY] are only filled on LIST, [goExploreY] only on DETAIL,
 * [selectRowY] and [goActive] only on SELECT. [tabY] is the centre row of the 探險 tab pill, which is where the
 * sheet is grabbed to move it.
 */
data class ExpFrame(
    val screen: ExpScreen,
    val cells: List<Cell> = emptyList(),
    val goExploreY: Int? = null,
    val selectRowY: Int? = null,
    val goActive: Boolean = false,
    /** The saturated fraction of the GO bubble that [goActive] was decided on (SELECT only; for the diagnostic log). */
    val goSat: Double = 0.0,
    val tabY: Int? = null,
)

/**
 * Recognises the 探險 screens and their list cells from a captured frame. Every rule is a fraction
 * of the frame's width or height, measured on real screenshots (1220x2712). Pixel positions are
 * `(fraction * size).toInt()`; "bottom-anchored" means `H - (fraction * W).toInt()`.
 *
 * Deliberately free of `android.*`, like the rest of `vision/`, so it runs under plain JUnit.
 */
object ExpeditionVision {

    /**
     * Screen priority: RESULT, then SELECT, then DETAIL, then LIST, then OTHER. [bottomInset] rows at the bottom are the
     * navigation bar, not the game: the game is the rows above them, and every rule is measured on those.
     */
    fun analyze(img: RgbImage, bottomInset: Int = 0): ExpFrame = analyzeGame(img.cropBottom(bottomInset))

    private fun analyzeGame(img: RgbImage): ExpFrame {
        val hsv = DoubleArray(3)
        if (isResult(img, hsv)) return ExpFrame(ExpScreen.RESULT)
        selectRowY(img, hsv)?.let {
            val sat = goSaturation(img, hsv)
            return ExpFrame(ExpScreen.SELECT, selectRowY = it, goActive = sat >= GO_ACTIVE_FRACTION, goSat = sat)
        }
        goExploreY(img, hsv)?.let { return ExpFrame(ExpScreen.DETAIL, goExploreY = it) }
        val tab = listTab(img, hsv) ?: return ExpFrame(ExpScreen.OTHER)
        return ExpFrame(ExpScreen.LIST, cells = listCells(img, tab.last, hsv), tabY = (tab.first + tab.last) / 2)
    }

    /** GO bubble, bottom-right of the SELECT screen. */
    fun goTap(w: Int, h: Int): Pair<Int, Int> = (0.848 * w).toInt() to bottomAnchored(h, 0.152, w)

    /** Solid green ✕ that closes the RESULT screen. */
    fun closeTap(w: Int, h: Int): Pair<Int, Int> = (0.096 * w).toInt() to bottomAnchored(h, 0.097, w)

    /**
     * Where the ✕ of RESULT is tapped: the centre of the dark-green ring in the corner of the game area, when there is one
     * (a phone's own screen may not match the measured point); otherwise the measured point.
     */
    fun closeTarget(img: RgbImage, bottomInset: Int = 0): Pair<Int, Int> {
        val game = img.cropBottom(bottomInset)
        val measured = closeTap(game.width, game.height)
        return ringCentre(game, measured) ?: measured
    }

    /** The dark green of the RESULT ✕: the colour rule RESULT itself is measured with. */
    private fun isDarkGreen(c: DoubleArray): Boolean = c[0] > 120.0 && c[0] < 160.0 && c[1] > 0.35 && c[2] > 0.25 && c[2] < 0.6

    /** Smallest dark-green component (in pixels) that counts as the ✕ ring. */
    private const val MIN_RING_PIXELS = 800

    /**
     * Centroid of the dark-green component nearest [near] in the corner box of the game area (the left fifth, the bottom
     * 30%), or null when no component is big enough.
     */
    private fun ringCentre(img: RgbImage, near: Pair<Int, Int>): Pair<Int, Int>? {
        val y0 = (0.70 * img.height).toInt()
        val bw = (0.2 * img.width).toInt()
        val bh = img.height - y0
        val hsv = DoubleArray(3)
        val mask = BooleanArray(bw * bh) { i ->
            ColorMath.toHsv(img.get(i % bw, y0 + i / bw), hsv)
            isDarkGreen(hsv)
        }
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val dx = intArrayOf(1, -1, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1)
        var best: Pair<Int, Int>? = null
        var bestDistance = Double.MAX_VALUE
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            var count = 0L
            var sumX = 0L
            var sumY = 0L
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                val x = i % bw
                val y = i / bw
                count++
                sumX += x
                sumY += y
                for (d in 0 until 4) {
                    val nx = x + dx[d]
                    val ny = y + dy[d]
                    if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue
                    val j = ny * bw + nx
                    if (mask[j] && !seen[j]) {
                        seen[j] = true
                        stack[top++] = j
                    }
                }
            }
            if (count < MIN_RING_PIXELS) continue
            val cx = (sumX / count).toInt()
            val cy = y0 + (sumY / count).toInt()
            val distance = hypot((cx - near.first).toDouble(), (cy - near.second).toDouble())
            if (distance < bestDistance) {
                bestDistance = distance
                best = cx to cy
            }
        }
        return best
    }

    /** Back / 取消, bottom-left on DETAIL and SELECT. */
    fun backTap(w: Int, h: Int): Pair<Int, Int> = (0.097 * w).toInt() to bottomAnchored(h, 0.097, w)

    /** 自動 pill, on the colour-filter dot row. */
    fun autoTap(w: Int, selectRowY: Int): Pair<Int, Int> = (0.242 * w).toInt() to selectRowY

    private fun bottomAnchored(h: Int, fraction: Double, w: Int): Int = h - (fraction * w).toInt()

    /** Fraction of pixels in the inclusive rectangle whose HSV satisfies [match]. */
    private inline fun fraction(
        img: RgbImage, x0: Int, y0: Int, x1: Int, y1: Int, hsv: DoubleArray, match: (DoubleArray) -> Boolean,
    ): Double {
        var total = 0
        var hit = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                total++
                ColorMath.toHsv(img.get(x, y), hsv)
                if (match(hsv)) hit++
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    private inline fun pixelIs(img: RgbImage, x: Int, y: Int, hsv: DoubleArray, match: (DoubleArray) -> Boolean): Boolean {
        ColorMath.toHsv(img.get(x, y), hsv)
        return match(hsv)
    }

    private fun isWhite(c: DoubleArray): Boolean = c[2] > 0.95 && c[1] < 0.05

    /** Dark green ✕ bottom-left: at least 20% of that patch is green-ish and mid-dark. */
    private fun isResult(img: RgbImage, hsv: DoubleArray): Boolean {
        val w = img.width
        val h = img.height
        val frac = fraction(img, (0.02 * w).toInt(), bottomAnchored(h, 0.166, w), (0.12 * w).toInt(), bottomAnchored(h, 0.022, w), hsv) {
            isDarkGreen(it)
        }
        return frac >= 0.2
    }

    /** The colour-filter dot row: first row where a red, yellow, blue and purple dot all sit. */
    private fun selectRowY(img: RgbImage, hsv: DoubleArray): Int? {
        val w = img.width
        val h = img.height
        val xRed = (0.629 * w).toInt()
        val xYellow = (0.712 * w).toInt()
        val xBlue = (0.796 * w).toInt()
        val xPurple = (0.879 * w).toInt()
        for (y in (0.2 * h).toInt()..(0.5 * h).toInt()) {
            if (pixelIs(img, xRed, y, hsv) { (it[0] < 15.0 || it[0] > 345.0) && it[1] > 0.45 } &&
                pixelIs(img, xYellow, y, hsv) { it[0] > 40.0 && it[0] < 60.0 && it[1] > 0.5 } &&
                pixelIs(img, xBlue, y, hsv) { it[0] > 200.0 && it[0] < 225.0 && it[1] > 0.45 } &&
                pixelIs(img, xPurple, y, hsv) { it[0] > 285.0 && it[0] < 310.0 && it[1] > 0.45 }
            ) return y
        }
        return null
    }

    /** The saturated fraction of the GO bubble: it is bright once 自動 is picked, and faded before (see [GO_ACTIVE_FRACTION]). */
    private fun goSaturation(img: RgbImage, hsv: DoubleArray): Double {
        val w = img.width
        val h = img.height
        return fraction(img, (0.767 * w).toInt(), bottomAnchored(h, 0.234, w), (0.933 * w).toInt(), bottomAnchored(h, 0.078, w), hsv) {
            it[1] > 0.5
        }
    }

    /** Outlined green 前往探險 button: first and last green rows on the centre column, a button tall apart. */
    private fun goExploreY(img: RgbImage, hsv: DoubleArray): Int? {
        val h = img.height
        val x = (0.5 * img.width).toInt()
        var first = -1
        var last = -1
        for (y in (0.55 * h).toInt()..(0.85 * h).toInt()) {
            if (pixelIs(img, x, y, hsv) { it[0] in 140.0..175.0 && it[1] > 0.35 }) {
                if (first < 0) first = y
                last = y
            }
        }
        if (first < 0) return null
        val span = last - first
        return if (span in (0.03 * h).toInt()..(0.06 * h).toInt()) (first + last) / 2 else null
    }

    /**
     * The selected 探險 tab: a green pill covering most of [0.57W, 0.72W], 0.02H–0.045H tall, with
     * near-white rows 0.008H above and below it. Returns the pill's rows.
     */
    private fun listTab(img: RgbImage, hsv: DoubleArray): IntRange? {
        val w = img.width
        val h = img.height
        val x0 = (0.57 * w).toInt()
        val x1 = (0.72 * w).toInt()
        val pad = (0.008 * h).toInt()
        val minLen = (0.02 * h).toInt()
        val maxLen = (0.045 * h).toInt()
        val tabRow = BooleanArray(h) { y ->
            fraction(img, x0, y, x1, y, hsv) { it[0] in 140.0..175.0 && it[1] > 0.35 && it[2] > 0.4 } >= 0.3
        }
        return runs(tabRow).firstOrNull { run ->
            val len = run.last - run.first + 1
            len in minLen..maxLen && run.first - pad >= 0 && run.last + pad < h &&
                isWhiteRow(img, x0, x1, run.first - pad, hsv) && isWhiteRow(img, x0, x1, run.last + pad, hsv)
        }
    }

    private fun isWhiteRow(img: RgbImage, x0: Int, x1: Int, y: Int, hsv: DoubleArray): Boolean =
        fraction(img, x0, y, x1, y, hsv) { isWhite(it) } >= 0.9

    /** Maximal runs of `true` as inclusive index ranges. */
    private fun runs(flags: BooleanArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in 0..flags.size) {
            val on = i < flags.size && flags[i]
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                out.add(start until i)
                start = -1
            }
        }
        return out
    }

    /**
     * The icon runs (global rows) the cell finder reads in each of the three columns of a LIST frame, before the gutter
     * and kind checks. For the diagnostics of a failed frame test; the same runs [listCells] classifies.
     */
    internal fun listColumnRuns(img: RgbImage): List<Pair<Int, List<IntRange>>> {
        val hsv = DoubleArray(3)
        val tab = listTab(img, hsv) ?: return emptyList()
        return columnRuns(img, tab.last, hsv)
    }

    /** The icon runs of each of the three columns below the tab row ([tabBottom]), before the shared-row pass. */
    private fun columnRuns(img: RgbImage, tabBottom: Int, hsv: DoubleArray): List<Pair<Int, List<IntRange>>> {
        val w = img.width
        val h = img.height
        val bandTop = tabBottom + (0.02 * h).toInt()
        val bandBottom = bottomAnchored(h, 0.178, w)
        return listOf(0.1856, 0.5, 0.8144).map { fx ->
            val cx = (fx * w).toInt()
            cx to iconRuns(img, cx, bandTop, bandBottom, hsv)
        }
    }

    /** Cells of a LIST screen, in reading order. */
    private fun listCells(img: RgbImage, tabBottom: Int, hsv: DoubleArray): List<Cell> {
        val cells = ArrayList<Cell>()
        for ((cx, runs) in columnRuns(img, tabBottom, hsv)) {
            for (run in runs) {
                if (!gutterClear(img, cx, run, hsv)) continue
                val cy = (run.first + run.last) / 2
                val kind = when {
                    isCovered(img, cx, cy, hsv) -> ItemKind.COVERED
                    isInProgress(img, cx, run.first) -> ItemKind.IN_PROGRESS
                    else -> classify(img, cx, cy, hsv)
                }
                cells.add(Cell(cx, cy, kind))
            }
        }
        return cells.sortedWith(compareBy({ it.y }, { it.x }))
    }

    /**
     * Icon rows of one column's cell band (rows with ≥6% ink), runs at most 0.004H apart merged,
     * keeping those 0.035H–0.08H tall and not clipped by the content band. Global y ranges.
     */
    private fun iconRuns(img: RgbImage, cx: Int, bandTop: Int, bandBottom: Int, hsv: DoubleArray): List<IntRange> {
        if (bandBottom <= bandTop) return emptyList()
        val h = img.height
        val x0 = cx - (0.0778 * img.width).toInt()
        val x1 = cx + (0.05 * img.width).toInt()
        val bandWidth = x1 - x0 + 1
        val icon = BooleanArray(bandBottom - bandTop + 1) { i ->
            val y = bandTop + i
            var ink = 0
            for (x in x0..x1) {
                ColorMath.toHsv(img.get(x, y), hsv)
                if ((hsv[1] > 0.3 && hsv[2] > 0.25) || hsv[2] < 0.75) ink++
            }
            ink >= 0.06 * bandWidth
        }
        val maxGap = (0.004 * h).toInt()
        val merged = ArrayList<IntRange>()
        for (r in runs(icon)) {
            val prev = merged.lastOrNull()
            if (prev != null && r.first - prev.last <= maxGap) {
                merged[merged.size - 1] = prev.first..r.last
            } else {
                merged.add(r)
            }
        }
        val minH = (0.035 * h).toInt()
        val maxH = (0.08 * h).toInt()
        return merged.map { (it.first + bandTop)..(it.last + bandTop) }.filter { r ->
            val height = r.last - r.first + 1
            height in minH..maxH && r.first > bandTop + 1 && r.last < bandBottom - 1
        }
    }

    /**
     * A mushroom photo card spills colour into the gutter beside it; a real cell has white there. The strip is
     * narrow (±0.005W) so a running card's border, drawn in its own grid slot, does not reach it.
     */
    private fun gutterClear(img: RgbImage, cx: Int, run: IntRange, hsv: DoubleArray): Boolean {
        val w = img.width
        val half = (0.005 * w).toInt()
        for (fg in listOf(0.3428, 0.6572)) {
            val g = (fg * w).toInt()
            if (abs(g - cx).toDouble() > 0.2 * w) continue
            if (fraction(img, g - half, run.first, g + half, run.last, hsv) { isGutterWhite(it) } < 0.85) return false
        }
        return true
    }

    /** White for the gutter check only: looser than [isWhite], so a faint tint in the gutter still counts as white. */
    private fun isGutterWhite(c: DoubleArray): Boolean = c[2] > 0.93 && c[1] < 0.13

    /**
     * The game's flower button covers the top-right cell; that cell is never tapped. The button is yellow, so a cell counts
     * as covered only where that yellow is at the button too: a visible card in the same place is not covered.
     */
    private fun isCovered(img: RgbImage, cx: Int, cy: Int, hsv: DoubleArray): Boolean {
        val w = img.width
        val h = img.height
        if (abs(cx - 0.898 * w) >= 0.155 * w || abs(cy - 0.284 * h) >= 0.075 * w + 0.04 * h) return false
        val yellow = fraction(img, (0.82 * w).toInt(), cy - (0.05 * h).toInt(), (0.98 * w).toInt(), cy + (0.05 * h).toInt(), hsv) {
            it[0] in 35.0..50.0 && it[1] > 0.3 && it[2] > 0.6
        }
        return yellow >= 0.2
    }

    /**
     * A pale grey (230,231,230) card above the icon means the pot is already growing; never tapped. A band of grey rows
     * above the icon is a card only if it does not spread across most of the screen: a separator line of the list (a
     * recording shows them, soft-edged) does, and does not count.
     */
    private fun isInProgress(img: RgbImage, cx: Int, top: Int): Boolean {
        val w = img.width
        val x0 = cx - (0.11 * w).toInt()
        val x1 = cx + (0.11 * w).toInt()
        val reach = (0.04 * img.height).toInt()
        var greyest = -1 // while inside a band of grey rows: the most of the screen width one of its rows is grey across
        for (y in (top - reach).coerceAtLeast(0)..top) {
            var near = 0
            if (y < top) for (x in x0..x1) if (isNearGrey(img.get(x, y))) near++
            if (y < top && near >= 0.5 * (x1 - x0 + 1)) {
                var whole = 0
                for (x in 0 until w) if (isNearGrey(img.get(x, y))) whole++
                greyest = maxOf(greyest, whole)
            } else if (greyest >= 0) {
                if (greyest < SEPARATOR_FRACTION * w) return true
                greyest = -1
            }
        }
        return false
    }

    private fun isNearGrey(p: Int): Boolean =
        abs(((p ushr 16) and 0xFF) - 230) <= 8 && abs(((p ushr 8) and 0xFF) - 231) <= 8 && abs((p and 0xFF) - 230) <= 8

    /** GIFT, POT, FRUIT or UNKNOWN, from the colours inside the icon box. */
    private fun classify(img: RgbImage, cx: Int, cy: Int, hsv: DoubleArray): ItemKind {
        val w = img.width
        val h = img.height
        var total = 0
        var soil = 0
        var red = 0
        var box = 0
        var saturated = 0
        var dark = 0
        for (y in (cy - (0.035 * h).toInt())..(cy + (0.035 * h).toInt())) {
            for (x in (cx - (0.0778 * w).toInt())..(cx + (0.05 * w).toInt())) {
                total++
                ColorMath.toHsv(img.get(x, y), hsv)
                val hue = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                if (v > 0.12 && v < 0.42 && s > 0.2 && (hue < 40.0 || hue > 340.0)) soil++
                if ((hue < 12.0 || hue > 348.0) && s > 0.55 && v > 0.55) red++
                if (s < 0.07 && v > 0.78 && v < 0.975) box++
                if (s > 0.3) saturated++
                if (v < 0.35) dark++
            }
        }
        val n = total.toDouble()
        return when {
            box / n >= 0.18 && red / n >= 0.08 -> ItemKind.GIFT
            soil / n >= 0.012 -> ItemKind.POT
            saturated / n >= 0.3 || dark / n >= DARK_FRUIT_FRACTION -> ItemKind.FRUIT
            else -> ItemKind.UNKNOWN
        }
    }

    /**
     * A dark fruit (a plum) is nearly unsaturated, so its saturation says nothing; but its icon box is dark where a pot,
     * a gift or any other fruit is not (at most a few per cent). This share of the box below value 0.35 counts as fruit.
     */
    private const val DARK_FRUIT_FRACTION = 0.15

    /** A row grey across this share of the screen width is a separator line of the list, not an in-progress card. */
    private const val SEPARATOR_FRACTION = 0.6
}
