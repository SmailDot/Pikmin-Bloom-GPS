package app.pikminbloom.gps.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pixel geometry of the spotlight tour on a 1080x2000 screen, with a 24 px hole padding, 48 px margin, 600x300 card. */
class SpotlightLayoutTest {
    private val screen = Size(1080, 2000)
    private val padding = 24
    private val margin = 48
    private val card = Size(600, 300)

    private fun place(target: Bounds?, screen: Size = this.screen, card: Size = this.card) =
        SpotlightLayout.place(screen, target, padding, margin, card)

    @Test
    fun withoutATargetTheCardIsCentredAndNothingIsHighlighted() {
        val p = place(null)
        assertNull(p.hole)
        assertNull(p.ring)
        assertEquals(240, p.tooltipX) // (1080 - 600) / 2
        assertEquals(850, p.tooltipY) // (2000 - 300) / 2
    }

    @Test
    fun theHoleIsTheTargetGrownByThePadding() {
        val p = place(Bounds(400, 300, 600, 380))
        assertEquals(Bounds(376, 276, 624, 404), p.hole)
    }

    @Test
    fun theRingIsCentredOnTheTargetWithAVisibleRadiusOfHalfTheHolesLongerSide() {
        val p = place(Bounds(400, 300, 600, 380))
        // hole is 248 wide and 128 high: radius = 248 / 2
        assertEquals(Ring(cx = 500, cy = 340, radius = 124), p.ring)
    }

    @Test
    fun theCardGoesBelowTheHoleWhenItFits() {
        val p = place(Bounds(400, 300, 600, 380))
        assertEquals(404 + margin, p.tooltipY) // hole bottom + margin
    }

    @Test
    fun theCardGoesAboveTheHoleWhenTheTargetIsNearTheBottomEdge() {
        val p = place(Bounds(400, 1800, 600, 1900))
        // below would end at 1924 + 48 + 300 + 48 > 2000, so the card sits above: hole top 1776 - 48 - 300
        assertEquals(1776 - margin - card.height, p.tooltipY)
    }

    @Test
    fun theCardIsCentredVerticallyWhenNeitherSideFits() {
        val tallScreen = Size(1080, 400)
        val p = SpotlightLayout.place(tallScreen, Bounds(400, 150, 600, 250), padding, margin, Size(600, 200))
        // below: 274 + 48 + 200 + 48 > 400; above: 126 - 48 - 200 < margin; centred: (400 - 200) / 2
        assertEquals(100, p.tooltipY)
    }

    @Test
    fun theCardIsCentredOnTheTargetHorizontallyWhenThereIsRoom() {
        val p = place(Bounds(400, 300, 600, 380))
        assertEquals(500 - 300, p.tooltipX) // target centre 500, card 600 wide
    }

    @Test
    fun theCardIsKeptInsideTheMarginAtTheLeftEdge() {
        val p = place(Bounds(0, 300, 100, 380))
        assertEquals(margin, p.tooltipX)
    }

    @Test
    fun theCardIsKeptInsideTheMarginAtTheRightEdge() {
        val p = place(Bounds(980, 300, 1080, 380))
        assertEquals(1080 - margin - card.width, p.tooltipX)
    }

    @Test
    fun theCardStaysInsideTheMarginsForTargetsAllOverTheScreen() {
        for (x in listOf(0, 300, 540, 800, 980)) {
            for (y in listOf(0, 500, 1000, 1500, 1900)) {
                val p = place(Bounds(x, y, x + 100, y + 80))
                assertTrue("x of card for target ($x,$y): ${p.tooltipX}", p.tooltipX >= margin && p.tooltipX + card.width <= screen.width - margin)
                assertTrue("y of card for target ($x,$y): ${p.tooltipY}", p.tooltipY >= margin && p.tooltipY + card.height <= screen.height - margin)
            }
        }
    }

    @Test
    fun thePulseStartsAtTheRingAndFadesOutAtTheEnd() {
        assertEquals(Pulse(growth = 0, alpha = 255), SpotlightLayout.pulse(0f, maxGrowth = 12))
        assertEquals(Pulse(growth = 12, alpha = 0), SpotlightLayout.pulse(1f, maxGrowth = 12))
    }

    @Test
    fun aPointIsInTheHoleFromItsLeftTopEdgeUpToButNotIncludingItsRightBottomEdge() {
        val hole = Bounds(10, 20, 30, 40)
        assertTrue(hole.contains(10f, 20f))
        assertTrue(hole.contains(29.5f, 39.5f))
        assertFalse(hole.contains(30f, 40f))
        assertFalse(hole.contains(5f, 25f))
    }

    @Test
    fun thePulseIsHalfwayAtHalfwayThroughTheCycle() {
        assertEquals(Pulse(growth = 6, alpha = 127), SpotlightLayout.pulse(0.5f, maxGrowth = 12))
    }
}
