package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.vision.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private const val W = 1220
private const val H = 2712
private const val BLACK = 0xFF000000.toInt()

/** The ✕ of RESULT: a dark green (hue about 147, saturation 1, value 0.43), as the RESULT rule measures it. */
private const val DARK_GREEN = 0xFF006E32.toInt()

/**
 * A phone with a 3-button navigation bar: the game is drawn in the rows above it, so everything bottom-anchored sits
 * higher than on a gesture-navigation phone. The RESULT screen and its ✕ must still be found there.
 */
class ResultNavBarTest {

    /** A filled dark-green disc, the ✕, centred on ([cx], [cy]). */
    private fun withClose(cx: Int, cy: Int, radius: Int = 50): RgbImage {
        val img = RgbImage.blank(W, H, BLACK)
        for (y in cy - radius..cy + radius) {
            for (x in cx - radius..cx + radius) {
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius) img.pixels[y * W + x] = DARK_GREEN
            }
        }
        return img
    }

    @Test
    fun aResultWhoseCloseSitsAboveTheBarIsRecognisedOnlyWithTheBarHeight() {
        // The game area is H - 120 = 2592 rows; the ✕ sits 0.097W above its bottom.
        val img = withClose(117, H - 120 - 118)
        assertEquals(ExpScreen.RESULT, ExpeditionVision.analyze(img, bottomInset = 120).screen)
        assertNotEquals(ExpScreen.RESULT, ExpeditionVision.analyze(img, bottomInset = 0).screen)
    }

    @Test
    fun theCloseTapIsTheCentreOfTheRingWhereItIsDrawn() {
        val img = withClose(117, 2430)
        assertEquals(117 to 2430, ExpeditionVision.closeTarget(img, bottomInset = 120))
    }

    @Test
    fun withNoRingTheCloseTapIsTheMeasuredPointAboveTheBar() {
        assertEquals(ExpeditionVision.closeTap(W, H - 120), ExpeditionVision.closeTarget(RgbImage.blank(W, H), bottomInset = 120))
    }
}
