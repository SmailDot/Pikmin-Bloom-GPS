package app.pikminbloom.gps.feed

import app.pikminbloom.gps.vision.PngCodec
import app.pikminbloom.gps.vision.RgbImage
import java.io.File

/**
 * Loads the 自動餵精華 screenshots from `src/test/resources/feed/` into [RgbImage].
 *
 * Mirrors `expedition/ExpeditionImages.kt`. The folder is gitignored because the screenshots show the spoofed
 * area and the game account: tests must `assumeTrue` on [loadOrNull] and skip when absent.
 */
object FeedImages {

    const val DIR = "feed"

    /** Loads one PNG by file name (e.g. `feed_zoomed.png`), or null when the file is not on disk. */
    fun loadOrNull(name: String): RgbImage? {
        val dir = resourceDir() ?: return null
        val file = File(dir, name)
        return if (file.isFile) runCatching { PngCodec.read(file) }.getOrNull() else null
    }

    private fun resourceDir(): File? {
        val url = FeedImages::class.java.classLoader?.getResource(DIR) ?: return null
        return runCatching { File(url.toURI()) }.getOrNull()?.takeIf { it.isDirectory }
    }
}
