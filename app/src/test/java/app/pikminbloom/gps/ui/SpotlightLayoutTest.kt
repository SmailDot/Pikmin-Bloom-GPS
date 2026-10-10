package app.pikminbloom.gps.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pixel geometry of the spotlight tour on a 1080x2000 screen, with a 24 px padding, 48 px margin, 600x300 card. */
class SpotlightLayoutTest {
    private val screen = Size(1080, 2000)
    private val padding = 24
    private val margin = 48
    private val card = Size(600, 300)
    private val circleRadius = 168
    private val handSize = 48

    private fun place(
        target: Bounds?,
        shape: SpotShape = SpotShape.RECT,
        gesture: Gesture = Gesture.NONE,
        screen: Size = this.screen,
        card: Size = this.card,
    ) = SpotlightLayout.place(screen, target, shape, gesture, padding, circleRadius, handSize, margin, card)

    @Test
    fun withoutATargetTheCardIsCentredAndNothingIsHighlighted() {
        val p = place(null)
        assertNull(p.hole)
        assertNull(p.circle)
        assertNull(p.ring)
        assertNull(p.fingertip)
        assertEquals(240, p.tooltipX) // (1080 - 600) / 2
        assertEquals(850, p.tooltipY) // (2000 - 300) / 2
    }

    @Test
    fun aRectangularHoleIsTheTargetGrownByThePadding() {
        val p = place(Bounds(400, 300, 600, 380))
        assertEquals(Bounds(376, 276, 624, 404), p.hole)
        assertNull(p.circle)
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
        val p = place(Bounds(400, 150, 600, 250), screen = tallScreen, card = Size(600, 200))
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
    fun aCircularSpotIsTheFixedRadiusAroundTheCentreOfTheTarget() {
        // The map's whole bounds: only the circle at its centre is highlighted, never the whole map.
        val p = place(Bounds(0, 500, 1080, 1500), shape = SpotShape.CIRCLE, gesture = Gesture.LONG_PRESS)
        assertEquals(Ring(540, 1000, circleRadius), p.circle)
        assertEquals(Ring(540, 1000, circleRadius), p.ring)
        assertNull(p.hole)
    }

    @Test
    fun theCardGoesBelowACircleWithoutOverlappingIt() {
        val p = place(Bounds(0, 500, 1080, 1500), shape = SpotShape.CIRCLE)
        assertEquals(1000 + circleRadius + margin, p.tooltipY) // circle bottom + margin
    }

    @Test
    fun theCardGoesAboveACircleNearTheBottomWithoutOverlappingIt() {
        val p = place(Bounds(0, 1500, 1080, 2000), shape = SpotShape.CIRCLE)
        // circle spans 1582..1918; below would end past the screen, so the card sits above it
        assertEquals(1750 - circleRadius - margin - card.height, p.tooltipY)
        assertTrue("the card must end above the circle", p.tooltipY + card.height <= 1750 - circleRadius)
    }

    @Test
    fun theCardNeverCoversACircularSpotAnywhereOnTheScreen() {
        for (x in listOf(100, 540, 980)) {
            for (y in listOf(100, 500, 1000, 1500, 1900)) {
                val p = place(Bounds(x - 40, y - 40, x + 40, y + 40), shape = SpotShape.CIRCLE)
                val overlaps = p.tooltipX < x + circleRadius && p.tooltipX + card.width > x - circleRadius &&
                    p.tooltipY < y + circleRadius && p.tooltipY + card.height > y - circleRadius
                assertTrue("the card covers the circle centred at ($x,$y)", !overlaps)
            }
        }
    }

    @Test
    fun aTapOnARectangularSpotPutsTheFingertipOnItsBottomLeftCorner() {
        val p = place(Bounds(400, 300, 600, 380), gesture = Gesture.TAP)
        assertEquals(Point(376, 404), p.fingertip) // hole's left and bottom
    }

    @Test
    fun theCardStaysBelowTheHandOfATapOnARectangularSpot() {
        // the hand hangs from the corner fingertip: its box is (353,398)-(401,446), below the hole's bottom at 404
        val p = place(Bounds(400, 300, 600, 380), gesture = Gesture.TAP)
        assertEquals(446 + margin, p.tooltipY)
    }

    @Test
    fun aLongPressOnACircularSpotPutsTheFingertipOnItsCentre() {
        val p = place(Bounds(0, 500, 1080, 1500), shape = SpotShape.CIRCLE, gesture = Gesture.LONG_PRESS)
        assertEquals(Point(540, 1000), p.fingertip)
    }

    @Test
    fun aTapOnACircularSpotPutsTheFingertipOnItsBottomLeftEdge() {
        // radius 168 at (540,1000): 225 degrees is 168 * 0.7071 = 118.8 along each axis
        val p = place(Bounds(540 - 40, 1000 - 40, 540 + 40, 1000 + 40), shape = SpotShape.CIRCLE, gesture = Gesture.TAP)
        assertEquals(Point(540 - 119, 1000 + 119), p.fingertip)
    }

    @Test
    fun aSpotWithoutAGestureHasNoFingertipButStillHasItsHole() {
        val p = place(Bounds(400, 300, 600, 380), gesture = Gesture.NONE)
        assertNull(p.fingertip)
        assertEquals(Bounds(376, 276, 624, 404), p.hole)
    }

    @Test
    fun theHandsFingertipLandsOnThePointAndTheHandHangsLowerLeftOfIt() {
        // the icon's fingertip is at 48 % of its width and 12.5 % of its height: 23 and 6 px at 48 px
        assertEquals(Point(77, 194), SpotlightLayout.handOrigin(Point(100, 200), 48, screen))
    }

    @Test
    fun theHandIsKeptOnTheScreen() {
        assertEquals(Point(0, 1952), SpotlightLayout.handOrigin(Point(5, 1990), 48, screen))
    }

    @Test
    fun thePulseStartsAtTheRingAndFadesOutAtTheEnd() {
        assertEquals(Pulse(growth = 0, alpha = 255), SpotlightLayout.pulse(0f, maxGrowth = 12))
        assertEquals(Pulse(growth = 12, alpha = 0), SpotlightLayout.pulse(1f, maxGrowth = 12))
    }

    @Test
    fun thePulseIsHalfwayAtHalfwayThroughTheCycle() {
        assertEquals(Pulse(growth = 6, alpha = 127), SpotlightLayout.pulse(0.5f, maxGrowth = 12))
    }
}
