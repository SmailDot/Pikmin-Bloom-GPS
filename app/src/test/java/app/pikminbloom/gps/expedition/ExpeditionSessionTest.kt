package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.nectar.NectarIo
import app.pikminbloom.gps.vision.RgbImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val W = 1220
private const val H = 2712
private val BLACK = 0xFF000000.toInt()
private val WHITE = 0xFFFFFFFF.toInt()

/**
 * Drives [ExpeditionSession] through scripted screens. Mirrors `NectarCollectorTest`'s fake io:
 * each `frame()` shows the next [Scene] (the last one repeats), `analyze` returns that scene's
 * [ExpFrame], and every tap, swipe and back is recorded. Tap coordinates are for a 1220x2712 frame.
 */
class ExpeditionSessionTest {

    /** [missing]: io.frame() gives no image for this look (the capture had none). */
    private data class Scene(val frame: ExpFrame, val shade: Int = BLACK, val missing: Boolean = false)

    private data class Swipe(val x: Int, val fromY: Int, val toY: Int, val durationMs: Long)

    private class FakeIo(private val scenes: List<Scene>, private val cancelAfterTaps: Int? = null) : NectarIo {
        val taps = mutableListOf<Pair<Int, Int>>()
        val swipes = mutableListOf<Swipe>()
        val waits = mutableListOf<Long>()
        var backs = 0
        var shown: ExpFrame? = null
        private var cursor = 0
        private val images = HashMap<Int, RgbImage>()

        override suspend fun frame(): RgbImage? {
            val scene = scenes[minOf(cursor, scenes.lastIndex)]
            cursor++
            if (scene.missing) return null
            shown = scene.frame
            return images.getOrPut(scene.shade) { RgbImage.blank(W, H, scene.shade) }
        }

        override suspend fun tap(x: Int, y: Int): Boolean {
            taps += x to y
            return true
        }

        override suspend fun swipe(x: Int, fromY: Int, toY: Int, durationMs: Long): Boolean {
            swipes += Swipe(x, fromY, toY, durationMs)
            return true
        }

        override suspend fun back(): Boolean {
            backs++
            return true
        }

        override suspend fun wait(ms: Long) {
            waits += ms
            if (cancelAfterTaps != null && taps.size >= cancelAfterTaps) {
                throw CancellationException("test: cancelled while waiting")
            }
        }
    }

    private fun list(vararg cells: Cell, tabY: Int? = null) = ExpFrame(ExpScreen.LIST, cells = cells.toList(), tabY = tabY)

    private fun select(goActive: Boolean) = ExpFrame(ExpScreen.SELECT, selectRowY = 1031, goActive = goActive)

    private val detail = ExpFrame(ExpScreen.DETAIL, goExploreY = 2003)

    private val resultScreen = ExpFrame(ExpScreen.RESULT)

    private val other = ExpFrame(ExpScreen.OTHER)

    private fun session(io: FakeIo, log: (String) -> Unit = {}) =
        ExpeditionSession(io, analyze = { _ -> checkNotNull(io.shown) }, log = log)

    private fun runSession(io: FakeIo, target: ExpeditionTarget, maxDispatch: Int): ExpeditionResult =
        runBlocking { session(io).run(target, maxDispatch) }

    // Tap points for a 1220x2712 frame, from ExpeditionVision's fractions.
    private val goExploreTap = 610 to 2003
    private val autoTap = 295 to 1031
    private val goTap = 1034 to 2527
    private val closeTap = 117 to 2594
    private val backTap = 118 to 2594

    @Test
    fun `a screen that is never the list stops at NOT_ON_LIST without any tap`() {
        val io = FakeIo(listOf(Scene(detail)))
        val result = runSession(io, ExpeditionTarget.BOTH, maxDispatch = 1)
        assertEquals(ExpeditionResult(0, ExpeditionStop.NOT_ON_LIST), result)
        assertEquals(emptyList<Pair<Int, Int>>(), io.taps)
    }

    @Test
    fun `three looks that are not the list stop at NOT_ON_LIST after exactly three waits of 700 ms`() {
        val io = FakeIo(listOf(Scene(other)))
        assertEquals(ExpeditionResult(0, ExpeditionStop.NOT_ON_LIST), runSession(io, ExpeditionTarget.POT, maxDispatch = 1))
        assertEquals(listOf(700L, 700L, 700L), io.waits)
    }

    @Test
    fun `a screen still fading in is looked at again and the run goes on once it is the list`() {
        val io = FakeIo(
            listOf(
                Scene(other), Scene(other),
                Scene(list(Cell(226, 1000, ItemKind.POT))),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        assertEquals(ExpeditionResult(1, ExpeditionStop.LIMIT_REACHED), runSession(io, ExpeditionTarget.POT, maxDispatch = 1))
        assertEquals(226 to 1000, io.taps.first())
    }

    @Test
    fun `a sheet whose tab row sits in the gesture zone is swiped from just above that row`() {
        // tabY 2620 on a 2712-high screen: 2620 - 94 (0.035H) = 2526, to 0.40H.
        val io = FakeIo(
            listOf(
                Scene(list(tabY = 2620), shade = BLACK),
                Scene(list(Cell(226, 1000, ItemKind.POT)), shade = WHITE),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        assertEquals(ExpeditionResult(1, ExpeditionStop.LIMIT_REACHED), runSession(io, ExpeditionTarget.POT, maxDispatch = 1))
        assertEquals(Swipe(610, 2526, 1084, 600L), io.swipes.first())
    }

    @Test
    fun `a sheet whose tab row is higher up is swiped from 0_85H as before`() {
        val io = FakeIo(
            listOf(
                Scene(list(tabY = 1232), shade = BLACK),
                Scene(list(Cell(226, 1000, ItemKind.POT)), shade = WHITE),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        assertEquals(ExpeditionResult(1, ExpeditionStop.LIMIT_REACHED), runSession(io, ExpeditionTarget.POT, maxDispatch = 1))
        assertEquals(Swipe(610, 2305, 1084, 600L), io.swipes.first())
    }

    @Test
    fun `a swipe that leaves the list bouncing still finds the fruit once the list settles`() {
        val io = FakeIo(
            listOf(
                Scene(list(), shade = BLACK),
                Scene(other, shade = WHITE), Scene(other, shade = WHITE),
                Scene(list(Cell(993, 1632, ItemKind.FRUIT)), shade = WHITE),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        assertEquals(ExpeditionResult(1, ExpeditionStop.LIMIT_REACHED), runSession(io, ExpeditionTarget.FRUIT, maxDispatch = 1))
        assertEquals(993 to 1632, io.taps.first())
    }

    @Test
    fun `a swipe followed by three looks that are not the list stops at LOST, not at the end of the list`() {
        // The OTHER frames are the same pixels as before the swipe: only a LIST frame may be compared, so a
        // screen that is not the list must not read as "unchanged, end of list".
        val io = FakeIo(listOf(Scene(list(), shade = BLACK), Scene(other, shade = BLACK)))
        assertEquals(ExpeditionResult(0, ExpeditionStop.LOST), runSession(io, ExpeditionTarget.POT, maxDispatch = 1))
        assertEquals(emptyList<Pair<Int, Int>>(), io.taps)
        assertEquals(1, io.swipes.size)
    }

    @Test
    fun `on a phone with a navigation bar the go and close taps move up by the bar`() {
        val io = FakeIo(
            listOf(
                Scene(list(Cell(226, 1000, ItemKind.POT))),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        runBlocking {
            ExpeditionSession(io, analyze = { _ -> checkNotNull(io.shown) }, bottomInsetFraction = 120.0 / H)
                .run(ExpeditionTarget.POT, maxDispatch = 1)
        }
        assertEquals(listOf(226 to 1000, goExploreTap, autoTap, 1034 to 2407, 117 to 2474), io.taps)
    }

    @Test
    fun `on a phone with a navigation bar the back tap moves up by the bar too`() {
        val io = FakeIo(
            listOf(
                Scene(list(Cell(226, 1000, ItemKind.POT))),
                Scene(detail), Scene(select(false)), Scene(select(false)), Scene(detail), Scene(list()),
            ),
        )
        runBlocking {
            ExpeditionSession(io, analyze = { _ -> checkNotNull(io.shown) }, bottomInsetFraction = 120.0 / H)
                .run(ExpeditionTarget.POT, maxDispatch = 5)
        }
        assertEquals(listOf(226 to 1000, goExploreTap, autoTap, 118 to 2474, 118 to 2474), io.taps)
    }

    @Test
    fun `a run logs each list look, each of our taps, the select state after auto and its stop, and nothing else`() {
        val lines = mutableListOf<String>()
        val io = FakeIo(
            listOf(
                Scene(list(Cell(226, 1000, ItemKind.POT), tabY = 2400)),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        runBlocking { session(io) { lines += it }.run(ExpeditionTarget.POT, maxDispatch = 1) }
        assertEquals(
            listOf(
                "list: tabY=2400, cells=[POT×1, FRUIT×0, GIFT×0, COVERED×0, IN_PROGRESS×0, UNKNOWN×0]",
                "tap cell POT at 226,1000",
                "tap explore at 610,2003",
                "tap auto at 295,1031",
                "select: goActive=true, goSat=0.000",
                "tap go at 1034,2527",
                "tap close at 117,2594",
                "list: tabY=-, cells=[POT×0, FRUIT×0, GIFT×0, COVERED×0, IN_PROGRESS×0, UNKNOWN×0]",
                "dispatched 1",
                "stopped: LIMIT_REACHED after 1",
            ),
            lines,
        )
    }

    @Test
    fun `a look that misses the list logs the screen it saw and the frame size`() {
        val lines = mutableListOf<String>()
        val io = FakeIo(
            listOf(
                Scene(other),
                Scene(list(Cell(226, 1000, ItemKind.POT))),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        runBlocking { session(io) { lines += it }.run(ExpeditionTarget.POT, maxDispatch = 1) }
        assertTrue("log was $lines", lines.contains("looked for LIST, saw OTHER (1220x2712)"))
    }

    @Test
    fun `a look that gives no frame is logged as no frame and the run goes on`() {
        val lines = mutableListOf<String>()
        val io = FakeIo(
            listOf(
                Scene(other, missing = true),
                Scene(list(Cell(226, 1000, ItemKind.POT))),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        val result = runBlocking { session(io) { lines += it }.run(ExpeditionTarget.POT, maxDispatch = 1) }
        assertEquals(ExpeditionResult(1, ExpeditionStop.LIMIT_REACHED), result)
        assertTrue("log was $lines", lines.contains("no frame"))
    }

    @Test
    fun `fruit target skips pots, gifts, unknown, covered and in-progress cells`() {
        val io = FakeIo(
            listOf(
                Scene(list(
                    Cell(226, 1000, ItemKind.POT), Cell(610, 1151, ItemKind.GIFT),
                    Cell(993, 1400, ItemKind.UNKNOWN), Cell(993, 1500, ItemKind.COVERED),
                    Cell(226, 1700, ItemKind.IN_PROGRESS), Cell(993, 1632, ItemKind.FRUIT),
                )),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        val result = runSession(io, ExpeditionTarget.FRUIT, maxDispatch = 1)
        assertEquals(ExpeditionResult(1, ExpeditionStop.LIMIT_REACHED), result)
        assertEquals(listOf(993 to 1632, goExploreTap, autoTap, goTap, closeTap), io.taps)
    }

    @Test
    fun `both target taps the earliest pot or fruit in reading order`() {
        val io = FakeIo(
            listOf(
                Scene(list(Cell(993, 1632, ItemKind.FRUIT), Cell(226, 1156, ItemKind.POT))),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        runSession(io, ExpeditionTarget.BOTH, maxDispatch = 1)
        assertEquals(226 to 1156, io.taps.first())
    }

    @Test
    fun `faded go bubble after auto backs out to the list and stops at NO_PIKMIN`() {
        val io = FakeIo(
            listOf(
                Scene(list(Cell(226, 1000, ItemKind.POT))),
                Scene(detail), Scene(select(false)), Scene(select(false)), Scene(detail), Scene(list()),
            ),
        )
        val result = runSession(io, ExpeditionTarget.POT, maxDispatch = 5)
        assertEquals(ExpeditionResult(0, ExpeditionStop.NO_PIKMIN), result)
        assertEquals(listOf(226 to 1000, goExploreTap, autoTap, backTap, backTap), io.taps)
    }

    @Test
    fun `reaching the dispatch limit stops at LIMIT_REACHED after that many dispatches`() {
        val io = FakeIo(
            listOf(
                Scene(list(Cell(226, 1000, ItemKind.POT))),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen),
                Scene(list(Cell(226, 1500, ItemKind.POT))),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        assertEquals(ExpeditionResult(2, ExpeditionStop.LIMIT_REACHED), runSession(io, ExpeditionTarget.POT, maxDispatch = 2))
    }

    @Test
    fun `unchanged frame after a swipe means the end of the list`() {
        val io = FakeIo(listOf(Scene(list()), Scene(list())))
        assertEquals(ExpeditionResult(0, ExpeditionStop.DONE_END_OF_LIST), runSession(io, ExpeditionTarget.BOTH, maxDispatch = 1))
        assertEquals(emptyList<Pair<Int, Int>>(), io.taps)
        assertEquals(1, io.swipes.size)
    }

    @Test
    fun `empty list swipes up and dispatches the cell that scrolls into view`() {
        val io = FakeIo(
            listOf(
                Scene(list(), shade = BLACK),
                Scene(list(Cell(226, 1000, ItemKind.POT)), shade = WHITE),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        val result = runSession(io, ExpeditionTarget.POT, maxDispatch = 1)
        assertEquals(ExpeditionResult(1, ExpeditionStop.LIMIT_REACHED), result)
        assertEquals(listOf(Swipe(610, 2305, 1084, 600L)), io.swipes)
        assertEquals(226 to 1000, io.taps.first())
    }

    @Test
    fun `detail screen that never appears after a cell tap stops at LOST with a back`() {
        val io = FakeIo(listOf(Scene(list(Cell(226, 1000, ItemKind.POT)))))
        assertEquals(ExpeditionResult(0, ExpeditionStop.LOST), runSession(io, ExpeditionTarget.POT, maxDispatch = 1))
        assertEquals(listOf(226 to 1000), io.taps)
        assertEquals(1, io.backs)
    }

    @Test
    fun `cancellation during a wait propagates and no further tap follows`() {
        val io = FakeIo(listOf(Scene(list(Cell(226, 1000, ItemKind.POT)))), cancelAfterTaps = 1)
        assertThrows(CancellationException::class.java) { runSession(io, ExpeditionTarget.POT, maxDispatch = 1) }
        assertEquals(listOf(226 to 1000), io.taps)
    }

    @Test
    fun `dispatchedSoFar matches the dispatch count after a limit-reached run`() {
        val io = FakeIo(
            listOf(
                Scene(list(Cell(226, 1000, ItemKind.POT))),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen), Scene(list()),
            ),
        )
        val session = session(io)
        runBlocking { session.run(ExpeditionTarget.POT, maxDispatch = 1) }
        assertEquals(1, session.dispatchedSoFar)
    }

    @Test
    fun `dispatchedSoFar still counts the dispatches made before a cancellation`() {
        val io = FakeIo(
            listOf(
                Scene(list(Cell(226, 1000, ItemKind.POT))),
                Scene(detail), Scene(select(true)), Scene(select(true)), Scene(resultScreen),
                Scene(list(Cell(226, 1500, ItemKind.POT))),
            ),
            cancelAfterTaps = 6,
        )
        val session = session(io)
        assertThrows(CancellationException::class.java) { runBlocking { session.run(ExpeditionTarget.POT, maxDispatch = 5) } }
        assertEquals(1, session.dispatchedSoFar)
    }
}
