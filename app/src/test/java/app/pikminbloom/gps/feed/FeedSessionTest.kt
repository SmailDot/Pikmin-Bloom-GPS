package app.pikminbloom.gps.feed

import app.pikminbloom.gps.vision.RgbImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val W = 1220
private const val H = 2712

/**
 * Drives [FeedSession] through scripted frames and bloom results. Each `frame()` gives the next scripted frame
 * (the last one repeats), `isFeed` is "is the feed frame", and each `blooms` call gives the next scripted result.
 * Every gesture and wait is recorded, in order.
 */
class FeedSessionTest {

    private sealed class Call {
        data class Pinch(val w: Int, val h: Int) : Call()
        data class Drag(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val radius: Int, val holdMs: Long) : Call()
        data class Path(val points: List<Pair<Int, Int>>, val durationMs: Long) : Call()
        data class Wait(val ms: Long) : Call()
    }

    private class FakeFeedIo(
        private val frames: List<RgbImage?>,
        private val pinchOk: Boolean = true,
        private val cancelAfterWaits: Int? = null,
    ) : FeedIo {
        val calls = mutableListOf<Call>()
        private var cursor = 0

        override suspend fun frame(): RgbImage? {
            val img = frames[minOf(cursor, frames.lastIndex)]
            cursor++
            return img
        }

        override suspend fun pinch(w: Int, h: Int): Boolean {
            calls += Call.Pinch(w, h)
            return pinchOk
        }

        override suspend fun dragHoldCircle(fromX: Int, fromY: Int, toX: Int, toY: Int, radius: Int, holdMs: Long): Boolean {
            calls += Call.Drag(fromX, fromY, toX, toY, radius, holdMs)
            return true
        }

        override suspend fun path(points: List<Pair<Int, Int>>, durationMs: Long): Boolean {
            calls += Call.Path(points, durationMs)
            return true
        }

        override suspend fun wait(ms: Long) {
            calls += Call.Wait(ms)
            if (cancelAfterWaits != null && calls.count { it is Call.Wait } >= cancelAfterWaits) {
                throw CancellationException("test: cancelled while waiting")
            }
        }

        fun gestures(): List<Call> = calls.filter { it !is Call.Wait }
        fun waits(): List<Long> = calls.filterIsInstance<Call.Wait>().map { it.ms }
    }

    private val feed = RgbImage.blank(W, H, 0xFF000000.toInt())
    private val other = RgbImage.blank(W, H, 0xFFFFFFFF.toInt())

    private val pinch = Call.Pinch(W, H)
    private val drag = Call.Drag(610, 2427, 610, 1464, 48, 4000L)
    private val p1 = 500 to 900
    private val p2 = 800 to 1500
    private val p3 = 300 to 1200
    private val p4 = 400 to 1300

    /** Scripted bloom results, one per `blooms` call: the round's look, then the sweep check after each harvest. */
    private fun session(io: FakeFeedIo, blooms: List<List<Pair<Int, Int>>>, log: (String) -> Unit = {}): FeedSession {
        val queue = blooms.toMutableList()
        return FeedSession(
            io,
            isFeed = { it === feed },
            blooms = { _, _ -> if (queue.isEmpty()) emptyList() else queue.removeAt(0) },
            log = log,
        )
    }

    private fun run(io: FakeFeedIo, blooms: List<List<Pair<Int, Int>>>, rounds: Int): FeedResult =
        runBlocking { session(io, blooms).run(rounds) }

    private fun harvest(start: Pair<Int, Int>) = Call.Path(FeedGestures.spiral(W, H, start), 6000L)

    @Test
    fun `a run that never sees the feed screen stops at NOT_ON_FEED without any gesture`() {
        val io = FakeFeedIo(listOf(other))
        assertEquals(FeedResult(0, FeedStop.NOT_ON_FEED), run(io, emptyList(), rounds = 1))
        assertEquals(emptyList<Call>(), io.gestures())
        assertEquals(listOf(700L, 700L, 700L), io.waits())
    }

    @Test
    fun `one round pinches, drags and holds on the feed point, then harvests from the first bloom`() {
        val io = FakeFeedIo(listOf(feed))
        assertEquals(FeedResult(1, FeedStop.DONE), run(io, listOf(listOf(p1, p2), emptyList()), rounds = 1))
        assertEquals(listOf(pinch, drag, harvest(p1)), io.gestures())
    }

    @Test
    fun `no bloom in round 1 stops at NO_BLOOM with no harvest`() {
        val io = FakeFeedIo(listOf(feed))
        assertEquals(FeedResult(0, FeedStop.NO_BLOOM), run(io, listOf(emptyList(), emptyList(), emptyList()), rounds = 3))
        assertEquals(listOf(pinch, drag), io.gestures())
    }

    @Test
    fun `two rounds in a row without a bloom stop at NO_BLOOM, after the rounds that found one`() {
        val io = FakeFeedIo(listOf(feed))
        val blooms = listOf(listOf(p1), emptyList()) + List(6) { emptyList() }
        assertEquals(FeedResult(2, FeedStop.NO_BLOOM), run(io, blooms, rounds = 5))
        assertEquals(listOf(pinch, drag, harvest(p1), pinch, drag, pinch, drag), io.gestures())
    }

    @Test
    fun `a single round without a bloom is skipped and the run carries on`() {
        val io = FakeFeedIo(listOf(feed))
        val blooms = listOf(listOf(p1), emptyList()) + List(3) { emptyList() } + listOf(listOf(p2))
        assertEquals(FeedResult(3, FeedStop.DONE), run(io, blooms, rounds = 3))
        assertEquals(listOf(pinch, drag, harvest(p1), pinch, drag, pinch, drag, harvest(p2)), io.gestures())
    }

    @Test
    fun `a later round that never shows the feed screen stops at LOST`() {
        // Round 1 completes; round 2 polls three frames that are not the feed screen.
        val io = FakeFeedIo(listOf(feed, feed, feed, other, other, other))
        assertEquals(FeedResult(1, FeedStop.LOST), run(io, listOf(listOf(p1), emptyList()), rounds = 2))
        assertEquals(listOf(pinch, drag, harvest(p1)), io.gestures())
    }

    @Test
    fun `a round whose before-frame is not the feed screen stops at LOST`() {
        val io = FakeFeedIo(listOf(feed, other))
        assertEquals(FeedResult(0, FeedStop.LOST), run(io, emptyList(), rounds = 1))
        assertEquals(listOf(pinch), io.gestures())
    }

    @Test
    fun `the rounds limit stops the run at DONE after exactly that many rounds`() {
        val io = FakeFeedIo(listOf(feed))
        assertEquals(FeedResult(2, FeedStop.DONE), run(io, listOf(listOf(p1), emptyList(), listOf(p2), emptyList()), rounds = 2))
        assertEquals(listOf(pinch, drag, harvest(p1), pinch, drag, harvest(p2)), io.gestures())
    }

    @Test
    fun `a gesture the system refuses stops at LOST`() {
        val io = FakeFeedIo(listOf(feed), pinchOk = false)
        assertEquals(FeedResult(0, FeedStop.LOST), run(io, emptyList(), rounds = 1))
        assertEquals(listOf(pinch), io.gestures())
    }

    @Test
    fun `cancellation during a wait propagates and no gesture follows`() {
        val io = FakeFeedIo(listOf(feed), cancelAfterWaits = 1)
        assertThrows(CancellationException::class.java) { run(io, emptyList(), rounds = 1) }
        assertEquals(emptyList<Call>(), io.gestures())
    }

    @Test
    fun `blooms left after a harvest start a second pass on the first of them`() {
        val io = FakeFeedIo(listOf(feed))
        // Round's look finds p1; the sweep check after the harvest still finds p3; the check after pass 2 finds none.
        assertEquals(FeedResult(1, FeedStop.DONE), run(io, listOf(listOf(p1), listOf(p3), emptyList()), rounds = 1))
        assertEquals(listOf(pinch, drag, harvest(p1), harvest(p3)), io.gestures())
    }

    @Test
    fun `blooms still left after pass 3 do not start a fourth pass`() {
        val io = FakeFeedIo(listOf(feed))
        // Pass 1 starts on p1; the sweep finds p2 (pass 2) and p3 (pass 3); p4 is left but there is no fourth pass.
        assertEquals(FeedResult(1, FeedStop.DONE), run(io, listOf(listOf(p1), listOf(p2), listOf(p3), listOf(p4)), rounds = 1))
        assertEquals(listOf(pinch, drag, harvest(p1), harvest(p2), harvest(p3)), io.gestures())
    }

    @Test
    fun `no blooms left after the harvest means no extra pass`() {
        val io = FakeFeedIo(listOf(feed))
        assertEquals(FeedResult(1, FeedStop.DONE), run(io, listOf(listOf(p1), emptyList()), rounds = 1))
        assertEquals(listOf(pinch, drag, harvest(p1)), io.gestures())
    }

    @Test
    fun `an extra pass is logged as extra harvest pass 1`() {
        val lines = mutableListOf<String>()
        val io = FakeFeedIo(listOf(feed))
        runBlocking { session(io, listOf(listOf(p1), listOf(p3), emptyList())) { lines += it }.run(1) }
        assertTrue("log was $lines", lines.contains("extra harvest pass 1"))
    }

    @Test
    fun `a cancel during round 2 keeps the round that finished in roundsSoFar`() {
        // Waits per round: feed poll 700, pinch settle 1000, drag hold 1500, bloom look 1000, harvest settle 1500.
        // The sixth wait is round 2's feed poll.
        val io = FakeFeedIo(listOf(feed), cancelAfterWaits = 6)
        val session = session(io, listOf(listOf(p1), emptyList()))
        assertThrows(CancellationException::class.java) { runBlocking { session.run(3) } }
        assertEquals(1, session.roundsSoFar)
        assertEquals(listOf(pinch, drag, harvest(p1)), io.gestures())
    }
}
