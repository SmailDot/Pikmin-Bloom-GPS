package app.pikminbloom.gps.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** Pure geometry for the feed gestures, on a 1220x2712 screen. */
class FeedGesturesTest {

    @Test
    fun `pinch is two strokes along the middle row, both 650 ms`() {
        assertEquals(
            listOf(
                FeedGestures.Stroke(244, 1356, 524, 1356, 650L),
                FeedGestures.Stroke(976, 1356, 695, 1356, 650L),
            ),
            FeedGestures.pinch(1220, 2712),
        )
    }

    @Test
    fun `circle has 25 points and starts and ends on the point to the right of its centre`() {
        val circle = FeedGestures.circle(610, 1464, 48)
        assertEquals(25, circle.size)
        assertEquals(circle.first(), circle.last())
        assertEquals(658 to 1464, circle.first())
    }

    @Test
    fun `every circle point is one radius from its centre`() {
        for ((x, y) in FeedGestures.circle(610, 1464, 48)) {
            assertTrue("point ($x,$y) is ${hypot((x - 610).toDouble(), (y - 1464).toDouble())} from centre", abs(hypot((x - 610).toDouble(), (y - 1464).toDouble()) - 48) <= 1.0)
        }
    }

    @Test
    fun `spiral has 161 points and point 0 is the bloom it starts on`() {
        val spiral = FeedGestures.spiral(1220, 2712, 300 to 900)
        assertEquals(161, spiral.size)
        assertEquals(300 to 900, spiral.first())
    }

    @Test
    fun `spiral ends on the right-hand end of its horizontal radius`() {
        // Centre (610, 1464.48), rx 0.36W = 439.2, after 5 full turns: (610 + 439.2, 1464).
        assertEquals(1049 to 1464, FeedGestures.spiral(1220, 2712, 300 to 900).last())
    }

    @Test
    fun `spiral starts near its centre and stays inside its ellipse`() {
        val spiral = FeedGestures.spiral(1220, 2712, 300 to 900)
        val (x1, y1) = spiral[1]
        assertTrue("point 1 is ($x1,$y1), expected next to the centre", hypot((x1 - 610).toDouble(), (y1 - 1464).toDouble()) < 10)
        for ((x, y) in spiral.drop(1)) {
            // radii 0.36W = 439.2 and 0.25H = 678 (plus one pixel of rounding)
            assertTrue("($x,$y) is outside the ellipse", abs(x - 610) <= 440 && abs(y - 1464) <= 679)
        }
    }
}
