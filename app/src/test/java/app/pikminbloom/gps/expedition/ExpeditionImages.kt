package app.pikminbloom.gps.expedition

import app.pikminbloom.gps.vision.PngCodec
import app.pikminbloom.gps.vision.RgbImage
import java.io.File

/**
 * Loads the 探險 screenshots from `src/test/resources/expedition/` into [RgbImage].
 *
 * Mirrors `vision/TestImages.kt`. The folder is gitignored because the screenshots show the
 * spoofed area and the game account: tests must `assumeTrue` on [loadOrNull] and skip when absent.
 */
object ExpeditionImages {

    const val DIR = "expedition"

    /** Loads one PNG by file name (e.g. `list_top.png`), or null when the file is not on disk. */
    fun loadOrNull(name: String): RgbImage? {
        val dir = resourceDir() ?: return null
        val file = File(dir, name)
        return if (file.isFile) runCatching { PngCodec.read(file) }.getOrNull() else null
    }

    private fun resourceDir(): File? {
        val url = ExpeditionImages::class.java.classLoader?.getResource(DIR) ?: return null
        return runCatching { File(url.toURI()) }.getOrNull()?.takeIf { it.isDirectory }
    }
}
