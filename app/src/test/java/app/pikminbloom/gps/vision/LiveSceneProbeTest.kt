package app.pikminbloom.gps.vision

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A live frame the user reported as "5 flowers" while the scanner saw 1: two white daisies, a
 * yellow daisy, a purple bloom and a magenta pom-pom, plus a red UI marker dot that must NOT count.
 * Kept as a regression test for the pale-bloom / yellow / solid-disc rules (frame is gitignored;
 * the test skips when it is absent).
 */
class LiveSceneProbeTest {
    @Test
    fun `live_user5 - all five flowers, and not the red marker dot`() {
        val img = TestImages.loadOrNull("live_user5.png")
        assumeTrue("live_user5.png not present", img != null)
        val r = FlowerDetector().detect(img!!)
        val expected = mapOf("purple" to Pair(88, 1462), "magenta" to Pair(166, 1401), "white L" to Pair(136, 1591), "white C" to Pair(628, 1625), "yellow" to Pair(789, 1870))
        val missing = expected.filterValues { p -> r.hits.none { Math.hypot((it.centroidX - p.first).toDouble(), (it.centroidY - p.second).toDouble()) < 60 } }.keys
        val redDot = r.hits.any { Math.hypot((it.centroidX - 902).toDouble(), (it.centroidY - 2020).toDouble()) < 60 }
        println("live_user5: hits=${r.hits.size} missing=$missing redDot=$redDot")
        assertTrue("missing $missing", missing.isEmpty())
        assertTrue("red marker dot was taken for a flower", !redDot)
        assertTrue("unexpected extra hits: ${r.hits.size}", r.hits.size == expected.size)
    }
}
