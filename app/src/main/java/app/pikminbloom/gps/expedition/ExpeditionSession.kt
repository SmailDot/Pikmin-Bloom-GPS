package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.nectar.NectarIo
import app.pikminbloom.gps.vision.FrameDiff
import app.pikminbloom.gps.vision.RgbImage
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

enum class ExpeditionTarget { FRUIT, POT, BOTH }

enum class ExpeditionStop { DONE_END_OF_LIST, LIMIT_REACHED, NO_PIKMIN, NOT_ON_LIST, LOST, CANCELLED }

data class ExpeditionResult(val dispatched: Int, val stop: ExpeditionStop)

/**
 * One 探險 run through the game's own screens: LIST, tap a cell, DETAIL, 前往探險, SELECT, 自動,
 * GO, RESULT, ✕, back to LIST. Every tap comes after a fresh frame shows the screen it belongs to;
 * a screen that does not appear ends the run as [ExpeditionStop.LOST] rather than tapping blindly.
 * Every delay goes through [NectarIo.wait], so the state machine runs instantly under test.
 *
 * Single use: call [run] once per session. Cancellation is rethrown, not turned into a stop.
 */
class ExpeditionSession(
    private val io: NectarIo,
    /** The navigation bar's height as a fraction of the screen's height (0 with none): the game is the rows above it. */
    private val bottomInsetFraction: Double = 0.0,
    private val analyze: (RgbImage) -> ExpFrame = { ExpeditionVision.analyze(it, (bottomInsetFraction * it.height).roundToInt()) },
    private val log: (String) -> Unit = {},
) {
    private var dispatched = 0

    /** Dispatches made so far in this run. Still readable after a cancellation, when [run] throws. */
    val dispatchedSoFar: Int get() = dispatched

    suspend fun run(target: ExpeditionTarget, maxDispatch: Int): ExpeditionResult {
        require(maxDispatch >= 1) { "maxDispatch must be at least 1, was $maxDispatch" }
        val stop = try {
            drive(target, maxDispatch)
        } catch (e: CancellationException) {
            log("expedition cancelled after $dispatched")
            throw e
        }
        log(ExpeditionLines.stopped(stop, dispatched))
        return ExpeditionResult(dispatched, stop)
    }

    /** A frame as shown now, with what it holds. */
    private class Look(val img: RgbImage, val frame: ExpFrame)

    private suspend fun drive(target: ExpeditionTarget, maxDispatch: Int): ExpeditionStop {
        // The screen can still be fading in when the run starts (a dialog's scrim, say): look a few times first.
        val first = poll(LIST_LOOKS, LIST_LOOK_MS, ExpScreen.LIST)?.let(::logList)
        if (first == null) {
            log("not on the 探險 list after $LIST_LOOKS looks")
            return ExpeditionStop.NOT_ON_LIST
        }
        var list: Look = settled(first) ?: first
        var swipes = 0
        while (true) {
            val cell = list.frame.cells.sortedWith(compareBy({ it.y }, { it.x })).firstOrNull { wanted(target, it.kind) }
            if (cell == null) {
                if (swipes == MAX_SWIPES) return ExpeditionStop.DONE_END_OF_LIST
                swipes++
                val before = list.img
                // A fully collapsed sheet puts its tab row in the gesture-navigation zone at the bottom: grab the
                // sheet just above its tabs (its drag handle) instead, or the swipe moves the map.
                val gameH = gameHeight(before)
                val fromY = list.frame.tabY
                    ?.takeIf { it > (0.85 * gameH).toInt() }
                    ?.let { it - (0.035 * gameH).toInt() }
                    ?: (0.80 * gameH).toInt()
                // A gentle swipe: a fling leaves the sheet gliding well past the settle looks.
                io.swipe((0.5 * before.width).toInt(), fromY, (0.45 * gameH).toInt(), SWIPE_MS)
                io.wait(SWIPE_SETTLE_MS)
                // The sheet may still glide after the swipe: only a LIST look that has stopped is read.
                val after = settled(list) ?: return ExpeditionStop.LOST
                if (FrameDiff.changedFraction(before, after.img) < 0.01) return ExpeditionStop.DONE_END_OF_LIST
                list = after
                continue
            }

            tapAt("cell ${cell.kind}", cell.x, cell.y)
            val detail = poll(POLL_FRAMES, POLL_MS, ExpScreen.DETAIL)
            if (detail == null) {
                io.back()
                return ExpeditionStop.LOST
            }
            val goExploreY = detail.frame.goExploreY ?: return ExpeditionStop.LOST
            tapAt("explore", (0.5 * detail.img.width).toInt(), goExploreY)
            val select = poll(POLL_FRAMES, POLL_MS, ExpScreen.SELECT) ?: return ExpeditionStop.LOST

            val selectRowY = select.frame.selectRowY ?: return ExpeditionStop.LOST
            val (autoX, autoY) = ExpeditionVision.autoTap(select.img.width, selectRowY)
            tapAt("auto", autoX, autoY)
            io.wait(SELECT_SETTLE_MS)
            val picked = look(ExpScreen.SELECT)
            picked?.let { log(ExpeditionLines.select(it.frame)) }
            if (picked == null || picked.frame.screen != ExpScreen.SELECT) return ExpeditionStop.LOST
            if (!picked.frame.goActive) return backToList(picked)
            val (goX, goY) = ExpeditionVision.goTap(picked.img.width, gameHeight(picked.img))
            tapAt("go", goX, goY)

            val shown = poll(RESULT_FRAMES, RESULT_POLL_MS, ExpScreen.RESULT) ?: return ExpeditionStop.LOST
            val (closeX, closeY) = ExpeditionVision.closeTarget(shown.img, insetPx(shown.img))
            tapAt("close", closeX, closeY)
            io.wait(CLOSE_SETTLE_MS)
            list = poll(POLL_FRAMES, POLL_MS, ExpScreen.LIST)?.let(::logList) ?: return ExpeditionStop.LOST

            dispatched++
            swipes = 0
            log("dispatched $dispatched")
            if (dispatched >= maxDispatch) return ExpeditionStop.LIMIT_REACHED
        }
    }

    /** The navigation bar's rows in [img], at the bottom; the game is the rows above them. */
    private fun insetPx(img: RgbImage): Int = (bottomInsetFraction * img.height).roundToInt()

    /** The height of the game in [img]: what the rules and the bottom-anchored taps measure against. */
    private fun gameHeight(img: RgbImage): Int = img.height - insetPx(img)

    /**
     * The list once it has stopped. Each LIST look is compared with the one before it (the first with [from]), and the first
     * that matches is read: a list still gliding after a swipe is smeared, so it is not. After [SETTLE_LOOKS] looks that never
     * match, the last LIST look is read. Null when no LIST look came at all.
     */
    private suspend fun settled(from: Look): Look? {
        var prev = from
        var seen: Look? = null
        for (n in 1..SETTLE_LOOKS) {
            io.wait(SETTLE_LOOK_MS)
            val now = look(ExpScreen.LIST) ?: continue
            if (now.frame.screen != ExpScreen.LIST) continue
            seen = now
            if (isStill(prev.img, now.img, contentRows(now.img, now.frame.tabY ?: prev.frame.tabY))) {
                log("list: still after $n looks")
                return now
            }
            prev = now
        }
        if (seen != null) log("list: never still in $SETTLE_LOOKS looks; using the last one")
        return seen
    }

    /** The content band of a LIST frame: from the tab row to the bottom band, or the whole game when that is empty. */
    private fun contentRows(img: RgbImage, tabY: Int?): IntRange {
        val top = tabY ?: 0
        val bottom = gameHeight(img) - (0.178 * img.width).toInt()
        return if (bottom > top) top until bottom else 0 until gameHeight(img)
    }

    /** One of our own taps, logged with its target and its point before it is made. */
    private suspend fun tapAt(what: String, x: Int, y: Int) {
        log(ExpeditionLines.tap(what, x, y))
        io.tap(x, y)
    }

    /** A LIST look, logged as how its cells counted (screen kinds only). */
    private fun logList(look: Look): Look {
        log(ExpeditionLines.list(look.frame))
        return look
    }

    private fun wanted(target: ExpeditionTarget, kind: ItemKind): Boolean = when (target) {
        ExpeditionTarget.FRUIT -> kind == ItemKind.FRUIT
        ExpeditionTarget.POT -> kind == ItemKind.POT
        ExpeditionTarget.BOTH -> kind == ItemKind.FRUIT || kind == ItemKind.POT
    }

    /** Back out of SELECT (or DETAIL) until the list shows: the team picker had no Pikmin to send. */
    private suspend fun backToList(start: Look): ExpeditionStop {
        var at = start
        repeat(BACK_TAPS) {
            if (at.frame.screen != ExpScreen.SELECT && at.frame.screen != ExpScreen.DETAIL) return ExpeditionStop.LOST
            val (x, y) = ExpeditionVision.backTap(at.img.width, gameHeight(at.img))
            tapAt("back", x, y)
            io.wait(POLL_MS)
            at = look(ExpScreen.LIST) ?: return ExpeditionStop.LOST
            if (at.frame.screen == ExpScreen.LIST) return ExpeditionStop.NO_PIKMIN
        }
        return ExpeditionStop.LOST
    }

    /**
     * One fresh look at the screen. A look that does not show [expect] is logged with the screen it did see and
     * the frame size; a look with no frame is logged as such. Pixels and coordinates are never logged.
     */
    private suspend fun look(expect: ExpScreen): Look? {
        val img = io.frame()
        if (img == null) {
            log("no frame")
            return null
        }
        val now = Look(img, analyze(img))
        if (now.frame.screen != expect) log("looked for $expect, saw ${now.frame.screen} (${img.width}x${img.height})")
        return now
    }

    /** Up to [frames] fresh looks, each after its own [gapMs] wait; the first one showing [screen], or null. */
    private suspend fun poll(frames: Int, gapMs: Long, screen: ExpScreen): Look? {
        repeat(frames) {
            io.wait(gapMs)
            val now = look(screen)
            if (now != null && now.frame.screen == screen) return now
        }
        return null
    }

    companion object {
        /** A list is still when less than this share of its content band's sampled pixels changed between two looks. */
        const val STILL_FRACTION = 0.01

        /** Whether the content rows of two LIST looks match, to within [STILL_FRACTION]. */
        fun isStill(prev: RgbImage, now: RgbImage, rows: IntRange): Boolean =
            FrameDiff.changedFraction(prev, now, rows = rows) < STILL_FRACTION

        const val MAX_SWIPES = 15
        const val SWIPE_MS = 900L
        const val SWIPE_SETTLE_MS = 1200L
        const val POLL_FRAMES = 3
        const val POLL_MS = 1000L
        const val SELECT_SETTLE_MS = 1200L
        const val RESULT_FRAMES = 12
        const val RESULT_POLL_MS = 1500L
        const val CLOSE_SETTLE_MS = 2000L
        const val BACK_TAPS = 3
        /** Looks for the list when a screen should be it: at the start of a run, and after each swipe (the sheet bounces). */
        const val LIST_LOOKS = 3
        const val LIST_LOOK_MS = 700L
        /** Looks at the list until it has stopped: at most this many, this far apart (a glide takes a second or two). */
        const val SETTLE_LOOKS = 6
        const val SETTLE_LOOK_MS = 500L
    }
}
