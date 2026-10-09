package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.vision.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Screen and cell recognition for the 探險 screens. The fixture rows are the measured expectations
 * for the real screenshots (gitignored, skipped when absent). Cells match on exact x, exact kind,
 * and y within 8 px; the full cell list must match, so an extra or missing cell fails.
 */
class ExpeditionVisionTest {

    private data class Expect(val x: Int, val y: Int, val kind: ItemKind)

    private fun fixture(name: String): RgbImage {
        val img = ExpeditionImages.loadOrNull(name)
        assumeTrue("$name not present", img != null)
        return checkNotNull(img)
    }

    private fun assertCells(expected: List<Expect>, actual: List<Cell>) {
        val left = actual.toMutableList()
        val missing = mutableListOf<Expect>()
        for (e in expected) {
            val i = left.indexOfFirst { it.x == e.x && it.kind == e.kind && abs(it.y - e.y) <= 8 }
            if (i < 0) missing += e else left.removeAt(i)
        }
        assertTrue("missing $missing; unexpected $left", missing.isEmpty() && left.isEmpty())
    }

    @Test
    fun `list_top - six cells in the first screen of the list`() {
        val frame = ExpeditionVision.analyze(fixture("list_top.png"))
        assertEquals(ExpScreen.LIST, frame.screen)
        assertCells(
            listOf(
                Expect(610, 1726, ItemKind.POT), Expect(993, 1726, ItemKind.POT),
                Expect(226, 1747, ItemKind.POT), Expect(610, 2207, ItemKind.POT),
                Expect(226, 2208, ItemKind.FRUIT), Expect(993, 2209, ItemKind.FRUIT),
            ),
            frame.cells,
        )
    }

    @Test
    fun `list_gifts - gift cells are reported as GIFT and the rest as POT or FRUIT`() {
        val frame = ExpeditionVision.analyze(fixture("list_gifts.png"))
        assertEquals(ExpScreen.LIST, frame.screen)
        assertCells(
            listOf(
                Expect(226, 674, ItemKind.POT), Expect(610, 674, ItemKind.POT),
                Expect(610, 1151, ItemKind.GIFT), Expect(993, 1151, ItemKind.GIFT),
                Expect(226, 1156, ItemKind.POT), Expect(226, 1632, ItemKind.GIFT),
                Expect(610, 1632, ItemKind.GIFT), Expect(993, 1632, ItemKind.GIFT),
                Expect(610, 2118, ItemKind.POT), Expect(226, 2139, ItemKind.POT),
                Expect(993, 2140, ItemKind.POT),
            ),
            frame.cells,
        )
    }

    @Test
    fun `list_end - covered and unknown cells are reported with their own kinds`() {
        val frame = ExpeditionVision.analyze(fixture("list_end.png"))
        assertEquals(ExpScreen.LIST, frame.screen)
        assertCells(
            listOf(
                Expect(610, 682, ItemKind.POT), Expect(226, 703, ItemKind.POT),
                Expect(993, 749, ItemKind.COVERED), Expect(226, 1163, ItemKind.POT),
                Expect(610, 1163, ItemKind.POT), Expect(993, 1166, ItemKind.FRUIT),
                Expect(226, 1645, ItemKind.POT), Expect(610, 1645, ItemKind.POT),
                Expect(993, 1647, ItemKind.FRUIT), Expect(226, 2292, ItemKind.UNKNOWN),
            ),
            frame.cells,
        )
    }

    @Test
    fun `list_collapsed - sheet collapsed at the top shows no pot fruit or gift cells`() {
        val frame = ExpeditionVision.analyze(fixture("list_collapsed.png"))
        assertEquals(ExpScreen.LIST, frame.screen)
        assertTrue(
            "unexpected cells ${frame.cells.filter { it.kind in setOf(ItemKind.POT, ItemKind.FRUIT, ItemKind.GIFT) }}",
            frame.cells.none { it.kind in setOf(ItemKind.POT, ItemKind.FRUIT, ItemKind.GIFT) },
        )
    }

    @Test
    fun `detail - the outlined go-explore button is found at y 2003`() {
        val frame = ExpeditionVision.analyze(fixture("detail.png"))
        assertEquals(ExpScreen.DETAIL, frame.screen)
        assertEquals(2003, frame.goExploreY)
    }

    @Test
    fun `select_empty - dot row at y 1031 and the go bubble is faded`() {
        val frame = ExpeditionVision.analyze(fixture("select_empty.png"))
        assertEquals(ExpScreen.SELECT, frame.screen)
        assertEquals(1031, frame.selectRowY)
        assertEquals(false, frame.goActive)
    }

    @Test
    fun `select_cancelled - same select screen as empty, go bubble still faded`() {
        val frame = ExpeditionVision.analyze(fixture("select_cancelled.png"))
        assertEquals(ExpScreen.SELECT, frame.screen)
        assertEquals(1031, frame.selectRowY)
        assertEquals(false, frame.goActive)
    }

    @Test
    fun `select_auto - go bubble is bright after the auto pill was tapped`() {
        val frame = ExpeditionVision.analyze(fixture("select_auto.png"))
        assertEquals(ExpScreen.SELECT, frame.screen)
        assertEquals(1031, frame.selectRowY)
        assertEquals(true, frame.goActive)
    }

    @Test
    fun `result - item card with the solid green close button is RESULT`() {
        assertEquals(ExpScreen.RESULT, ExpeditionVision.analyze(fixture("result.png")).screen)
    }

    @Test
    fun `result_anim - the animated result is still RESULT`() {
        assertEquals(ExpScreen.RESULT, ExpeditionVision.analyze(fixture("result_anim.png")).screen)
    }

    @Test
    fun `not_list_recap - the recap screen is OTHER`() {
        assertEquals(ExpScreen.OTHER, ExpeditionVision.analyze(fixture("not_list_recap.png")).screen)
    }

    @Test
    fun `not_list_mood - the mood screen is OTHER`() {
        assertEquals(ExpScreen.OTHER, ExpeditionVision.analyze(fixture("not_list_mood.png")).screen)
    }

    @Test
    fun `blank white frame is OTHER with no cells`() {
        val frame = ExpeditionVision.analyze(RgbImage.blank(1220, 2712, 0xFFFFFFFF.toInt()))
        assertEquals(ExpScreen.OTHER, frame.screen)
        assertTrue("unexpected cells ${frame.cells}", frame.cells.isEmpty())
    }

    @Test
    fun `thin green pill on white at 0_12H is the LIST tab with no cells`() {
        val w = 1220
        val h = 2712
        val img = RgbImage.blank(w, h, 0xFFFFFFFF.toInt())
        val green = 0xFF00C88C.toInt() // hue 162, s 1.0, v 0.78
        val top = (0.12 * h).toInt()
        val bottom = top + (0.03 * h).toInt() - 1
        for (y in top..bottom) for (x in (0.5 * w).toInt()..(0.8 * w).toInt()) img.pixels[y * w + x] = green
        val frame = ExpeditionVision.analyze(img)
        assertEquals(ExpScreen.LIST, frame.screen)
        assertTrue("unexpected cells ${frame.cells}", frame.cells.isEmpty())
    }
}
