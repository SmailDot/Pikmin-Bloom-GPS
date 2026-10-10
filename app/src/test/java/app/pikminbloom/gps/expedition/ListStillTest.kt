package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.vision.RgbImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val W = 1220
private const val H = 2712
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/**
 * The list is still when the rows of its content band changed by less than one per cent of the sampled pixels. The band is
 * rows 0..799, sampled every fourth row from row 2: 200 sample rows, so one changed row is 0.5% (still) and two are 1%
 * (not still, since one per cent is not under one per cent).
 */
class ListStillTest {

    private val band = 0 until 800

    /** A black frame with the given rows painted white, whole row by whole row. */
    private fun frameWithRows(rows: List<Int>): RgbImage {
        val pixels = IntArray(W * H) { BLACK }
        for (y in rows) for (x in 0 until W) pixels[y * W + x] = WHITE
        return RgbImage(W, H, pixels)
    }

    @Test
    fun aListOneSampleRowOutOfTwoHundredChangedIsStill() {
        assertTrue(ExpeditionSession.isStill(frameWithRows(emptyList()), frameWithRows(listOf(2)), band))
    }

    @Test
    fun aListTwoSampleRowsOutOfTwoHundredChangedIsNotStill() {
        assertFalse(ExpeditionSession.isStill(frameWithRows(emptyList()), frameWithRows(listOf(2, 6)), band))
    }

    @Test
    fun changesOutsideTheContentBandDoNotMakeAListMove() {
        // Row 1000 is below the band: the status and bottom bars around the list do not count.
        assertTrue(ExpeditionSession.isStill(frameWithRows(emptyList()), frameWithRows(listOf(1002, 1006, 1010)), band))
    }
}
