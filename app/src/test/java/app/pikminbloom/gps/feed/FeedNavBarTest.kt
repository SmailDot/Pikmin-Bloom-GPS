package app.pikminbloom.gps.feed

import app.pikminbloom.gps.vision.RgbImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The feed screen on a phone whose game is drawn above a 3-button navigation bar (fixture gitignored, skipped when absent). */
class FeedNavBarTest {

    private fun fixture(name: String): RgbImage {
        val img = FeedImages.loadOrNull(name)
        assumeTrue("$name not present", img != null)
        return checkNotNull(img)
    }

    /** The frame that phone shows: the game scaled into the rows above a dark bar of [bar] rows. */
    private fun framedAboveBar(img: RgbImage, bar: Int): RgbImage {
        val gameH = img.height - bar
        val pixels = IntArray(img.width * img.height) { 0xFF101010.toInt() }
        for (y in 0 until gameH) {
            val sy = (y.toLong() * img.height / gameH).toInt()
            for (x in 0 until img.width) pixels[y * img.width + x] = img.get(x, sy)
        }
        return RgbImage(img.width, img.height, pixels)
    }

    @Test
    fun theFeedScreenAboveABarIsRecognisedOnlyWithTheBarHeight() {
        val framed = framedAboveBar(fixture("feed_zoomed.png"), bar = 120)
        assertTrue(FeedVision.isFeedScreen(framed, bottomInset = 120))
        assertFalse("the bar's dark rows would read as the feed's whistle without the inset", FeedVision.isFeedScreen(framed, bottomInset = 0))
    }
}
