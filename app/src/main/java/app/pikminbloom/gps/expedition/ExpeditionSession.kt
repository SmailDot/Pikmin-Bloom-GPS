package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.nectar.NectarIo
import app.pikminbloom.gps.vision.FrameDiff
import app.pikminbloom.gps.vision.RgbImage
import kotlinx.coroutines.CancellationException

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
    private val analyze: (RgbImage) -> ExpFrame = ExpeditionVision::analyze,
    private val log: (String) -> Unit = {},
) {
    private var dispatched = 0

    suspend fun run(target: ExpeditionTarget, maxDispatch: Int): ExpeditionResult {
        require(maxDispatch >= 1) { "maxDispatch must be at least 1, was $maxDispatch" }
        val stop = try {
            drive(target, maxDispatch)
        } catch (e: CancellationException) {
            log("expedition cancelled after $dispatched")
            throw e
        }
        log("expedition stopped: $stop after $dispatched")
        return ExpeditionResult(dispatched, stop)
    }

    /** A frame as shown now, with what it holds. */
    private class Look(val img: RgbImage, val frame: ExpFrame)

    private suspend fun drive(target: ExpeditionTarget, maxDispatch: Int): ExpeditionStop {
        val first = look()
        if (first == null || first.frame.screen != ExpScreen.LIST) {
            log("first frame is not the 探險 list")
            return ExpeditionStop.NOT_ON_LIST
        }
        var list: Look = first
        var swipes = 0
        while (true) {
            val cell = list.frame.cells.sortedWith(compareBy({ it.y }, { it.x })).firstOrNull { wanted(target, it.kind) }
            if (cell == null) {
                if (swipes == MAX_SWIPES) return ExpeditionStop.DONE_END_OF_LIST
                swipes++
                val before = list.img
                io.swipe((0.5 * before.width).toInt(), (0.85 * before.height).toInt(), (0.40 * before.height).toInt(), SWIPE_MS)
                io.wait(SWIPE_SETTLE_MS)
                val after = look() ?: return ExpeditionStop.LOST
                if (FrameDiff.changedFraction(before, after.img) < 0.01) return ExpeditionStop.DONE_END_OF_LIST
                if (after.frame.screen != ExpScreen.LIST) return ExpeditionStop.LOST
                list = after
                continue
            }

            io.tap(cell.x, cell.y)
            val detail = poll(POLL_FRAMES, POLL_MS, ExpScreen.DETAIL)
            if (detail == null) {
                io.back()
                return ExpeditionStop.LOST
            }
            val goExploreY = detail.frame.goExploreY ?: return ExpeditionStop.LOST
            io.tap((0.5 * detail.img.width).toInt(), goExploreY)
            val select = poll(POLL_FRAMES, POLL_MS, ExpScreen.SELECT) ?: return ExpeditionStop.LOST

            val selectRowY = select.frame.selectRowY ?: return ExpeditionStop.LOST
            val (autoX, autoY) = ExpeditionVision.autoTap(select.img.width, selectRowY)
            io.tap(autoX, autoY)
            io.wait(SELECT_SETTLE_MS)
            val picked = look()
            if (picked == null || picked.frame.screen != ExpScreen.SELECT) return ExpeditionStop.LOST
            if (!picked.frame.goActive) return backToList(picked)
            val (goX, goY) = ExpeditionVision.goTap(picked.img.width, picked.img.height)
            io.tap(goX, goY)

            val shown = poll(RESULT_FRAMES, RESULT_POLL_MS, ExpScreen.RESULT) ?: return ExpeditionStop.LOST
            val (closeX, closeY) = ExpeditionVision.closeTap(shown.img.width, shown.img.height)
            io.tap(closeX, closeY)
            io.wait(CLOSE_SETTLE_MS)
            list = poll(POLL_FRAMES, POLL_MS, ExpScreen.LIST) ?: return ExpeditionStop.LOST

            dispatched++
            swipes = 0
            log("dispatched $dispatched")
            if (dispatched >= maxDispatch) return ExpeditionStop.LIMIT_REACHED
        }
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
            val (x, y) = ExpeditionVision.backTap(at.img.width, at.img.height)
            io.tap(x, y)
            io.wait(POLL_MS)
            at = look() ?: return ExpeditionStop.LOST
            if (at.frame.screen == ExpScreen.LIST) return ExpeditionStop.NO_PIKMIN
        }
        return ExpeditionStop.LOST
    }

    private suspend fun look(): Look? {
        val img = io.frame() ?: return null
        return Look(img, analyze(img))
    }

    /** Up to [frames] fresh looks, each after its own [gapMs] wait; the first one showing [screen], or null. */
    private suspend fun poll(frames: Int, gapMs: Long, screen: ExpScreen): Look? {
        repeat(frames) {
            io.wait(gapMs)
            val now = look()
            if (now != null && now.frame.screen == screen) return now
        }
        return null
    }

    private companion object {
        const val MAX_SWIPES = 15
        const val SWIPE_MS = 600L
        const val SWIPE_SETTLE_MS = 1200L
        const val POLL_FRAMES = 3
        const val POLL_MS = 1000L
        const val SELECT_SETTLE_MS = 1200L
        const val RESULT_FRAMES = 12
        const val RESULT_POLL_MS = 1500L
        const val CLOSE_SETTLE_MS = 2000L
        const val BACK_TAPS = 3
    }
}
