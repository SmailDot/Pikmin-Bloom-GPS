package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.vision.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `list_inprogress - a running card in its grid slot does not hide the pots beside it`() {
        // The running card's pink border falls inside the neighbours' gutter strips; the pots either side stay cells.
        val frame = ExpeditionVision.analyze(fixture("list_inprogress.png"))
        assertEquals(ExpScreen.LIST, frame.screen)
        assertCells(
            listOf(
                Expect(610, 1726, ItemKind.POT), Expect(993, 1726, ItemKind.POT),
                Expect(226, 1747, ItemKind.POT), Expect(226, 2207, ItemKind.POT),
                Expect(993, 2207, ItemKind.POT), Expect(610, 2349, ItemKind.IN_PROGRESS),
            ),
            frame.cells,
        )
    }

    /**
     * An expanded sheet with a 柳橙 to send, where the labels sit under each icon. The finder used to take the label with
     * its icon (the run ran on into the text), so the classify box landed on text and the cell read as UNKNOWN.
     */
    @Test
    fun `list_orange - every cell is found by its icon, not its label, and the scan reads no unknown cell`() {
        val img = fixture("list_orange.png")
        val frame = ExpeditionVision.analyze(img)
        // Centres are the midpoints of the icon ink runs (the rows' bottoms line up at 789, 1270, 1751, 2232). A pale top
        // makes a run start lower than its icon (the fruit at 993 in row three); a split pot takes its body's centre.
        val expected = listOf(
            Expect(226, 705, ItemKind.GIFT), Expect(610, 705, ItemKind.GIFT), Expect(993, 705, ItemKind.GIFT),
            Expect(226, 1186, ItemKind.GIFT), Expect(610, 1186, ItemKind.GIFT), Expect(993, 1186, ItemKind.GIFT),
            Expect(226, 1667, ItemKind.GIFT), Expect(610, 1693, ItemKind.POT), Expect(993, 1679, ItemKind.FRUIT),
            Expect(226, 2153, ItemKind.POT), Expect(610, 2153, ItemKind.POT), Expect(993, 2148, ItemKind.GIFT),
        )
        val left = frame.cells.toMutableList()
        val missing = mutableListOf<Expect>()
        for (e in expected) {
            val i = left.indexOfFirst { it.x == e.x && it.kind == e.kind && abs(it.y - e.y) <= 12 }
            if (i < 0) missing += e else left.removeAt(i)
        }
        // A partly visible bottom row may hold an in-progress or covered card; nothing else may be left over.
        val unexpected = left.filter { it.kind != ItemKind.IN_PROGRESS && it.kind != ItemKind.COVERED }
        val runs = ExpeditionVision.listColumnRuns(img)
        assertTrue("missing $missing; unexpected $unexpected; runs per column $runs; cells ${frame.cells}", missing.isEmpty() && unexpected.isEmpty())
    }

    /**
     * A community screen recording, rescaled to 1220 wide (so 2757 tall) and of lower quality. The dark plums (梅子：矮牽牛)
     * are nearly unsaturated, so they read as UNKNOWN unless their dark ink counts as fruit. Only the plums and the yellow
     * pot are asserted: the blurry red pot at the top left is left out.
     */
    @Test
    fun `list_plum_video - the dark plums are fruit and the yellow pot is a pot`() {
        val frame = ExpeditionVision.analyze(fixture("list_plum_video.png"))
        val expected = listOf(
            Expect(610, 620, ItemKind.FRUIT), Expect(993, 620, ItemKind.FRUIT),
            Expect(226, 1110, ItemKind.FRUIT), Expect(610, 1110, ItemKind.FRUIT),
            Expect(993, 1100, ItemKind.POT),
        )
        val missing = expected.filter { e ->
            frame.cells.none { it.x == e.x && it.kind == e.kind && abs(it.y - e.y) <= 25 }
        }
        assertTrue("missing $missing; cells ${frame.cells}", missing.isEmpty())
    }

    /**
     * A device frame at another scroll offset, with the game's floating scroll indicator (a white tab with ^ and v) on the
     * right edge. The app's own look read it as POT×3, GIFT×1, UNKNOWN×5, so the orange was never sent.
     */
    @Test
    fun `list_orange_run - the cells beside the scroll indicator are read, and the in-progress cards are never tapped`() {
        val img = fixture("list_orange_run.png")
        val frame = ExpeditionVision.analyze(img)
        val expected = listOf(
            Expect(226, 718, ItemKind.GIFT), Expect(610, 718, ItemKind.GIFT), Expect(993, 718, ItemKind.GIFT),
            Expect(226, 1206, ItemKind.GIFT), Expect(610, 1206, ItemKind.POT), Expect(993, 1222, ItemKind.FRUIT),
            Expect(226, 1690, ItemKind.POT), Expect(610, 1690, ItemKind.POT), Expect(993, 1690, ItemKind.GIFT),
            Expect(226, 2170, ItemKind.POT),
        )
        val left = frame.cells.toMutableList()
        val missing = mutableListOf<Expect>()
        for (e in expected) {
            val i = left.indexOfFirst { it.x == e.x && it.kind == e.kind && abs(it.y - e.y) <= 15 }
            if (i < 0) missing += e else left.removeAt(i)
        }
        // The two cards at the bottom right are pale and unfinished: UNKNOWN or IN_PROGRESS, never read as anything tappable.
        val unexpected = left.filter { it.kind != ItemKind.IN_PROGRESS && it.kind != ItemKind.UNKNOWN }
        assertTrue("missing $missing; unexpected $unexpected\n${ExpeditionVision.diagnose(img)}", missing.isEmpty() && unexpected.isEmpty())
    }

    /** A page of finished expeditions: cards marked 完成 and 領取, with coloured borders. None of them is to be tapped here. */
    @Test
    fun `list_done_cards - finished expedition cards are never read as a pot, fruit or gift`() {
        val img = fixture("list_done_cards.png")
        val frame = ExpeditionVision.analyze(img)
        val tappable = frame.cells.filter { it.kind != ItemKind.UNKNOWN && it.kind != ItemKind.IN_PROGRESS }
        assertTrue("cells ${frame.cells}; tappable $tappable\n${ExpeditionVision.diagnose(img)}", tappable.isEmpty())
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

    /** A 1220x2712 white frame with the thin green tab pill: 0.03H tall at 0.12H, rows 325..405. */
    private fun thinPillFrame(): RgbImage {
        val w = 1220
        val h = 2712
        val img = RgbImage.blank(w, h, 0xFFFFFFFF.toInt())
        val green = 0xFF00C88C.toInt() // hue 162, s 1.0, v 0.78
        val top = (0.12 * h).toInt()
        val bottom = top + (0.03 * h).toInt() - 1
        for (y in top..bottom) for (x in (0.5 * w).toInt()..(0.8 * w).toInt()) img.pixels[y * w + x] = green
        return img
    }

    @Test
    fun `list_top - the tab pill centre row is reported as tabY`() {
        // Expected 367, tolerance 6 px.
        val tabY = checkNotNull(ExpeditionVision.analyze(fixture("list_top.png")).tabY)
        assertTrue("tabY $tabY, expected 367 +-6", abs(tabY - 367) <= 6)
    }

    @Test
    fun `list_collapsed - the collapsed tab pill centre row is reported as tabY`() {
        // Expected 1232, tolerance 6 px.
        val tabY = checkNotNull(ExpeditionVision.analyze(fixture("list_collapsed.png")).tabY)
        assertTrue("tabY $tabY, expected 1232 +-6", abs(tabY - 1232) <= 6)
    }

    @Test
    fun `detail - a screen that is not the list has no tabY`() {
        assertNull(ExpeditionVision.analyze(fixture("detail.png")).tabY)
    }

    @Test
    fun `thin green pill on white reports its centre row 365 as tabY`() {
        assertEquals(365, ExpeditionVision.analyze(thinPillFrame()).tabY)
    }

    @Test
    fun `thin green pill on white at 0_12H is the LIST tab with no cells`() {
        val frame = ExpeditionVision.analyze(thinPillFrame())
        assertEquals(ExpScreen.LIST, frame.screen)
        assertTrue("unexpected cells ${frame.cells}", frame.cells.isEmpty())
    }
}
