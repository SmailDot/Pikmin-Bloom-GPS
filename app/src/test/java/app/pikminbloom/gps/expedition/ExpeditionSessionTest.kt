package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.nectar.NectarIo
import app.pikminbloom.gps.vision.RgbImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    private data class Scene(val frame: ExpFrame, val shade: Int = BLACK)

    private data class Swipe(val x: Int, val fromY: Int, val toY: Int, val durationMs: Long)

    private class FakeIo(private val scenes: List<Scene>, private val cancelAfterTaps: Int? = null) : NectarIo {
        val taps = mutableListOf<Pair<Int, Int>>()
        val swipes = mutableListOf<Swipe>()
        var backs = 0
        var shown: ExpFrame? = null
        private var cursor = 0
        private val images = HashMap<Int, RgbImage>()

        override suspend fun frame(): RgbImage {
            val scene = scenes[minOf(cursor, scenes.lastIndex)]
            cursor++
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
            if (cancelAfterTaps != null && taps.size >= cancelAfterTaps) {
                throw CancellationException("test: cancelled while waiting")
            }
        }
    }

    private fun list(vararg cells: Cell) = ExpFrame(ExpScreen.LIST, cells = cells.toList())

    private fun select(goActive: Boolean) = ExpFrame(ExpScreen.SELECT, selectRowY = 1031, goActive = goActive)

    private val detail = ExpFrame(ExpScreen.DETAIL, goExploreY = 2003)

    private val resultScreen = ExpFrame(ExpScreen.RESULT)

    private fun session(io: FakeIo) = ExpeditionSession(io, analyze = { _ -> checkNotNull(io.shown) })

    private fun runSession(io: FakeIo, target: ExpeditionTarget, maxDispatch: Int): ExpeditionResult =
        runBlocking { session(io).run(target, maxDispatch) }

    // Tap points for a 1220x2712 frame, from ExpeditionVision's fractions.
    private val goExploreTap = 610 to 2003
    private val autoTap = 295 to 1031
    private val goTap = 1034 to 2527
    private val closeTap = 117 to 2594
    private val backTap = 118 to 2594

    @Test
    fun `first frame not on the list stops at NOT_ON_LIST without any tap`() {
        val io = FakeIo(listOf(Scene(detail)))
        val result = runSession(io, ExpeditionTarget.BOTH, maxDispatch = 1)
        assertEquals(ExpeditionResult(0, ExpeditionStop.NOT_ON_LIST), result)
        assertEquals(emptyList<Pair<Int, Int>>(), io.taps)
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
}
