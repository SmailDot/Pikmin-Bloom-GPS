package app.pikminbloom.gps.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FrameDiffTest {
    private val green = 0xFF3A8F2E.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun paint(img: RgbImage, l: Int, t: Int, r: Int, b: Int, argb: Int) {
        for (y in t..b) for (x in l..r) img.pixels[y * img.width + x] = argb
    }

    @Test
    fun identicalFramesHaveNoChange() {
        val a = RgbImage.blank(200, 300, green)
        val b = RgbImage.blank(200, 300, green)
        assertEquals(0.0, FrameDiff.changedFraction(a, b), 0.0)
        assertNull(FrameDiff.changedBox(a, b, Box(0, 0, 199, 299)))
    }

    @Test
    fun fullyDifferentFramesChangeEverywhere() {
        val a = RgbImage.blank(200, 300, green)
        val b = RgbImage.blank(200, 300, white)
        assertEquals(1.0, FrameDiff.changedFraction(a, b), 0.0)
    }

    @Test
    fun smallBrightnessJitterIsNotAChange() {
        val a = RgbImage.blank(200, 300, green)
        val b = RgbImage.blank(200, 300, 0xFF3F9433.toInt())   // +5 on every channel
        assertEquals(0.0, FrameDiff.changedFraction(a, b), 0.0)
    }

    @Test
    fun changedBoxFindsTheRectangleThatAppeared() {
        val a = RgbImage.blank(400, 600, green)
        val b = RgbImage.blank(400, 600, green)
        paint(b, 120, 200, 279, 259, white)     // a 160x60 label
        val box = FrameDiff.changedBox(a, b, Box(0, 0, 399, 599))
        assertNotNull(box)
        // Sampling grid is coarse, so allow one step of slack on every edge.
        assertTrue("left ${box!!.left}", box.left in 116..128)
        assertTrue("top ${box.top}", box.top in 196..208)
        assertTrue("right ${box.right}", box.right in 272..283)
        assertTrue("bottom ${box.bottom}", box.bottom in 252..263)
        assertTrue("centerX ${box.centerX}", abs(box.centerX - 200) <= 8)
        assertTrue("centerY ${box.centerY}", abs(box.centerY - 230) <= 8)
    }

    @Test
    fun changedBoxIgnoresChangesOutsideTheRegion() {
        val a = RgbImage.blank(400, 600, green)
        val b = RgbImage.blank(400, 600, green)
        paint(b, 0, 500, 399, 599, white)       // a bar at the bottom, outside the region
        assertNull(FrameDiff.changedBox(a, b, Box(0, 0, 399, 399)))
    }

    @Test
    fun changedBoxNeedsMoreThanAFewStrayPixels() {
        val a = RgbImage.blank(400, 600, green)
        val b = RgbImage.blank(400, 600, green)
        paint(b, 100, 100, 104, 104, white)     // 5x5 speck
        assertNull(FrameDiff.changedBox(a, b, Box(0, 0, 399, 599), minSamples = 6))
    }

    @Test
    fun regionIsClampedToTheImage() {
        val a = RgbImage.blank(100, 100, green)
        val b = RgbImage.blank(100, 100, white)
        val box = FrameDiff.changedBox(a, b, Box(-50, -50, 500, 500))
        assertNotNull(box)
        assertTrue(box!!.left >= 0 && box.top >= 0 && box.right <= 99 && box.bottom <= 99)
    }

    @Test
    fun brightFractionMeasuresTheWhiteSheet() {
        val img = RgbImage.blank(400, 800, green)
        paint(img, 0, 440, 399, 799, white)      // bottom 45% white
        assertEquals(1.0, FrameDiff.brightFraction(img, Box(0, 480, 399, 799)), 0.01)
        assertEquals(0.0, FrameDiff.brightFraction(img, Box(0, 0, 399, 399)), 0.01)
        // saturated bright colours (sky, yellow flowers) are not "white sheet"
        paint(img, 0, 0, 399, 399, 0xFF4FA3E0.toInt())
        assertEquals(0.0, FrameDiff.brightFraction(img, Box(0, 0, 399, 399)), 0.01)
    }
}
