package app.pikminbloom.gps

import app.pikminbloom.gps.nectar.NectarCollector
import app.pikminbloom.gps.nectar.NectarIo
import app.pikminbloom.gps.nectar.NectarResult
import app.pikminbloom.gps.nectar.NectarStage
import app.pikminbloom.gps.vision.FlowerHit
import app.pikminbloom.gps.vision.PixelPoint
import app.pikminbloom.gps.vision.RgbImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collector never sees real game art; it works from "did something appear where I tapped".
 * These fakes script what the screen shows after each gesture.
 */
class NectarCollectorTest {
    private val w = 400
    private val h = 800
    private val green = 0xFF3A8F2E.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF202020.toInt()

    private fun frame(paint: (RgbImage) -> Unit = {}): RgbImage = RgbImage.blank(w, h, green).also(paint)
    private fun RgbImage.fill(l: Int, t: Int, r: Int, b: Int, argb: Int) {
        for (y in t..b) for (x in l..r) pixels[y * width + x] = argb
    }

    private fun hit(x: Int, y: Int) = FlowerHit(
        centroidX = x, centroidY = y, anchorX = x, anchorY = y + 20, areaPx = 400,
        left = x - 10, top = y - 10, right = x + 10, bottom = y + 10, hueDeg = 300.0, sat = 0.8, value = 0.9, stemFound = true,
    )

    /** Screens in order; the last one repeats. Every gesture advances to the next screen. */
    private class FakeIo(private val screens: List<RgbImage>) : NectarIo {
        val log = ArrayList<String>()
        private var i = 0
        private fun advance() { if (i < screens.size - 1) i++ }
        override suspend fun frame(): RgbImage? = screens[i]
        override suspend fun tap(x: Int, y: Int): Boolean { log += "tap($x,$y)"; advance(); return true }
        override suspend fun swipe(x: Int, fromY: Int, toY: Int, durationMs: Long): Boolean { log += "swipe($x,$fromY->$toY)"; advance(); return true }
        override suspend fun back(): Boolean { log += "back"; advance(); return true }
        override suspend fun wait(ms: Long) { log += "wait" }
    }

    private fun assertTapInside(entry: String, xs: IntRange, ys: IntRange) {
        val (x, y) = Regex("""tap\((\d+),(\d+)\)""").find(entry)!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
        assertTrue("$entry not inside $xs x $ys", x in xs && y in ys)
    }

    private fun collector(io: NectarIo, hits: List<FlowerHit>) =
        NectarCollector(io, detect = { hits }, log = {})

    @Test
    fun noFlowerNearTheAvatarFails() {
        val io = FakeIo(listOf(frame()))
        val r = runBlocking { collector(io, emptyList()).collect(PixelPoint(200.0, 400.0)) }
        assertEquals(NectarResult.Failed(NectarStage.FIND_FLOWER, "no flower near the avatar"), r)
        assertTrue(io.log.none { it.startsWith("tap") })
    }

    @Test
    fun flowerTooFarFromTheAvatarIsIgnored() {
        val io = FakeIo(listOf(frame()))
        val r = runBlocking { collector(io, listOf(hit(20, 20))).collect(PixelPoint(200.0, 400.0), maxDistancePx = 100) }
        assertTrue(r is NectarResult.Failed && (r as NectarResult.Failed).stage == NectarStage.FIND_FLOWER)
    }

    @Test
    fun tapWithoutALabelRetriesOnceThenFails() {
        val base = frame()
        val io = FakeIo(listOf(base, base, base))
        val r = runBlocking { collector(io, listOf(hit(200, 300))).collect(PixelPoint(200.0, 400.0)) }
        assertEquals(NectarStage.TAP_FLOWER, (r as NectarResult.Failed).stage)
        assertEquals(2, io.log.count { it.startsWith("tap") })
    }

    @Test
    fun labelWithoutAnInfoPageFails() {
        val base = frame()
        val labelled = frame { it.fill(150, 220, 260, 260, white) }   // label ~80 px above the flower head
        val io = FakeIo(listOf(base, labelled, labelled))
        val r = runBlocking { collector(io, listOf(hit(200, 300))).collect(PixelPoint(200.0, 400.0)) }
        assertEquals(NectarStage.TAP_LABEL, (r as NectarResult.Failed).stage)
        assertEquals("tap(200,320)", io.log.first { it.startsWith("tap") })    // flower anchor
        assertTapInside(io.log.filter { it.startsWith("tap") }[1], 150..260, 220..260)   // label centre
    }

    @Test
    fun happyPathTapsFlowerThenLabelSwipesAndCloses() {
        val base = frame()
        val labelled = frame { it.fill(150, 220, 260, 260, white) }
        val page = frame { it.fill(0, 0, w - 1, 439, grey); it.fill(0, 440, w - 1, h - 1, white) }   // photo + white sheet
        val afterSwipe = frame { it.fill(0, 0, w - 1, 439, grey); it.fill(0, 440, w - 1, h - 1, white); it.fill(100, 500, 300, 560, grey) }
        val io = FakeIo(listOf(base, labelled, page, afterSwipe, base))
        val r = runBlocking { collector(io, listOf(hit(200, 300))).collect(PixelPoint(200.0, 400.0)) }
        assertEquals(NectarResult.Collected, r)
        val gestures = io.log.filter { it != "wait" }
        assertEquals(4, gestures.size)
        assertEquals("tap(200,320)", gestures[0])
        assertTapInside(gestures[1], 150..260, 220..260)
        assertEquals("swipe(200,360->640)", gestures[2])
        assertEquals("back", gestures[3])
    }

    @Test
    fun pageThatDoesNotCloseIsReportedButNotFatal() {
        val base = frame()
        val labelled = frame { it.fill(150, 220, 260, 260, white) }
        val page = frame { it.fill(0, 0, w - 1, 439, grey); it.fill(0, 440, w - 1, h - 1, white) }
        val io = FakeIo(listOf(base, labelled, page, page, page, page))
        val r = runBlocking { collector(io, listOf(hit(200, 300))).collect(PixelPoint(200.0, 400.0)) }
        assertEquals(NectarResult.Failed(NectarStage.CLOSE, "info page still open"), r)
        assertEquals(2, io.log.count { it == "back" })
    }

    @Test
    fun hitsOnTopOfTheAvatarAreNotFlowers() {
        // The avatar marker / its pikmin sit within a few dozen px of the avatar point; a Big
        // Flower we walked to is at the circle edge, further out. Tapping the avatar toggles the view.
        val io = FakeIo(listOf(frame()))
        val r = runBlocking { collector(io, listOf(hit(210, 405))).collect(PixelPoint(200.0, 400.0)) }
        assertEquals(NectarStage.FIND_FLOWER, (r as NectarResult.Failed).stage)
        assertTrue(io.log.none { it.startsWith("tap") })
    }

    @Test
    fun aWholeRegionChangingIsTheMapMovingNotALabel() {
        val base = frame()
        val moved = frame { it.fill(0, 0, w - 1, h - 1, 0xFF8B5A2B.toInt()) }   // every pixel repainted
        val io = FakeIo(listOf(base, moved, moved))
        val r = runBlocking { collector(io, listOf(hit(200, 300))).collect(PixelPoint(200.0, 400.0)) }
        assertEquals(NectarStage.TAP_FLOWER, (r as NectarResult.Failed).stage)
    }

    @Test
    fun aViewSwitchWithoutAWhiteSheetIsNotTheInfoPage() {
        val base = frame()
        val labelled = frame { it.fill(150, 220, 260, 260, white) }
        val lobby = frame { it.fill(0, 0, w - 1, h - 1, 0xFF4FA3E0.toInt()); it.fill(0, 500, w - 1, h - 1, green) }  // sky + meadow
        val io = FakeIo(listOf(base, labelled, lobby, lobby))
        val r = runBlocking { collector(io, listOf(hit(200, 300))).collect(PixelPoint(200.0, 400.0)) }
        assertEquals(NectarStage.TAP_LABEL, (r as NectarResult.Failed).stage)
    }
}
