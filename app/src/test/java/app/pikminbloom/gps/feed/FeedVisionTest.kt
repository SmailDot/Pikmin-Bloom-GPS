package app.pikminbloom.gps.feed

import app.pikminbloom.gps.vision.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

private const val W = 1220
private const val H = 2712
private val DARK = 0xFF141414.toInt()
private val GREY = 0xFF787878.toInt() // 120: a mid-grey sample that brightens to yellow
private val WHITE = 0xFFFFFFFF.toInt()
private val YELLOW = 0xFFFFE63C.toInt() // hue about 52, s about 0.77, v 1.0: a glowing bloom

/**
 * The feed-screen check and the bloom finder. The fixture rows are the feed screens (gitignored, skipped when
 * absent) and the expedition screens the feed check must reject. The bloom tests paint synthetic frames on the
 * grid samples, so they always run.
 */
class FeedVisionTest {

    private fun fixture(name: String): RgbImage {
        val img = FeedImages.loadOrNull(name)
        assumeTrue("$name not present", img != null)
        return checkNotNull(img)
    }

    private fun sample(i: Int, j: Int): Pair<Int, Int> = FeedVision.bloomSamples(W, H)[j * FeedTuning.GRID_COLS + i]

    private fun paint(img: RgbImage, at: Pair<Int, Int>, colour: Int, half: Int = 4) {
        for (y in at.second - half..at.second + half) for (x in at.first - half..at.first + half) {
            img.pixels[y * img.width + x] = colour
        }
    }

    @Test
    fun `feed_zoomed - the zoomed-out feed screen is recognised`() {
        assertTrue(FeedVision.isFeedScreen(fixture("feed_zoomed.png")))
    }

    @Test
    fun `feed_selected - the feed screen with a Pikmin selected is recognised`() {
        assertTrue(FeedVision.isFeedScreen(fixture("feed_selected.png")))
    }

    @Test
    fun `feed_bloom - the feed screen with a bloom on it is recognised`() {
        assertTrue(FeedVision.isFeedScreen(fixture("feed_bloom.png")))
    }

    @Test
    fun `feed_after - the feed screen after feeding is recognised`() {
        assertTrue(FeedVision.isFeedScreen(fixture("feed_after.png")))
    }

    @Test
    fun `list_top - the 探險 list is not the feed screen`() {
        assertFalse(FeedVision.isFeedScreen(fixture("list_top.png")))
    }

    @Test
    fun `select_auto - the 探險 select screen is not the feed screen`() {
        assertFalse(FeedVision.isFeedScreen(fixture("select_auto.png")))
    }

    @Test
    fun `result - the 探險 result screen is not the feed screen`() {
        assertFalse(FeedVision.isFeedScreen(fixture("result.png")))
    }

    @Test
    fun `a blank frame is not the feed screen`() {
        assertFalse(FeedVision.isFeedScreen(RgbImage.blank(W, H, DARK)))
    }

    @Test
    fun `a yellow glow that appeared on a dark frame is a bloom at its sample point`() {
        val point = sample(3, 2)
        val before = RgbImage.blank(W, H, DARK)
        val after = RgbImage.blank(W, H, DARK)
        paint(after, point, YELLOW)
        assertEquals(listOf(point), FeedVision.findBlooms(before, after))
    }

    @Test
    fun `a whole-frame brightness shift is a camera move and gives no bloom`() {
        val before = RgbImage.blank(W, H, DARK)
        val after = RgbImage.blank(W, H, WHITE)
        assertEquals(emptyList<Pair<Int, Int>>(), FeedVision.findBlooms(before, after))
    }

    @Test
    fun `a bright square that got darker is not a bloom`() {
        val before = RgbImage.blank(W, H, DARK)
        paint(before, sample(3, 2), YELLOW)
        val after = RgbImage.blank(W, H, DARK)
        assertEquals(emptyList<Pair<Int, Int>>(), FeedVision.findBlooms(before, after))
    }

    @Test
    fun `candidates come strongest rise first`() {
        val strong = sample(2, 1) // dark before: rise 235
        val weak = sample(6, 4) // mid-grey before: rise 135
        val before = RgbImage.blank(W, H, DARK)
        paint(before, weak, GREY)
        val after = RgbImage.blank(W, H, DARK)
        paint(after, strong, YELLOW)
        paint(after, weak, YELLOW)
        assertEquals(listOf(strong, weak), FeedVision.findBlooms(before, after))
    }

    @Test
    fun `two candidates inside the dedupe box keep only the stronger`() {
        val kept = FeedVision.dedupe(listOf(FeedVision.Bloom(600, 1000, 200), FeedVision.Bloom(620, 1010, 150)), W, H)
        assertEquals(listOf(FeedVision.Bloom(600, 1000, 200)), kept)
    }

    @Test
    fun `two candidates outside the dedupe box are both kept`() {
        val kept = FeedVision.dedupe(listOf(FeedVision.Bloom(600, 1000, 200), FeedVision.Bloom(900, 1000, 150)), W, H)
        assertEquals(2, kept.size)
    }
}
