package app.pikminbloom.gps.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The game's area is the rows above the navigation bar: cropping the bar away keeps those rows as they are. */
class CropBottomTest {

    @Test
    fun cropBottomKeepsTheRowsAboveTheBarAndDropsTheRest() {
        val img = RgbImage(2, 4, intArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val game = img.cropBottom(1)
        assertEquals(2, game.width)
        assertEquals(3, game.height)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), game.pixels.toList())
    }

    @Test
    fun noBarMeansTheSameImage() {
        val img = RgbImage.blank(2, 2)
        assertSame(img, img.cropBottom(0))
    }
}
