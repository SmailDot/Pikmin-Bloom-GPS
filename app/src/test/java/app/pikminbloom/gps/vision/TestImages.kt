package app.pikminbloom.gps.vision

import java.io.File

/**
 * Loads the captured game screenshots from `src/test/resources/birdseye/` into [RgbImage].
 *
 * Decoding goes through [PngCodec] rather than `javax.imageio.ImageIO`: AGP compiles the `test`
 * source set against `android.jar`, which does not carry the JDK's `java.desktop` module, so
 * `javax.imageio` does not resolve at compile time here. See the note on [PngCodec]. Either way
 * the vision package needs no extra test dependency and no Robolectric.
 */
object TestImages {

    const val DIR = "birdseye"

    /** True when the capture folder exists and holds at least one PNG. */
    fun available(): Boolean = names().isNotEmpty()

    /** All PNG file names in the resource folder, sorted. */
    fun names(): List<String> {
        val dir = resourceDir() ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
    }

    fun birdsEyeNames(): List<String> = names().filter { it.startsWith("be_") }

    fun walkNames(): List<String> = names().filter { it.startsWith("walk_") }

    fun calibrationNames(): List<String> = names().filter { it.startsWith("cal_") }.sorted()

    /** Loads one PNG by file name (e.g. `be_1.png`). */
    fun load(name: String): RgbImage {
        val dir = requireNotNull(resourceDir()) { "test resource folder $DIR is missing" }
        val file = File(dir, name)
        require(file.isFile) { "missing test image ${file.absolutePath}" }
        return PngCodec.read(file)
    }

    fun loadOrNull(name: String): RgbImage? = runCatching { load(name) }.getOrNull()

    private fun resourceDir(): File? {
        val url = TestImages::class.java.classLoader?.getResource(DIR) ?: return null
        return runCatching { File(url.toURI()) }.getOrNull()?.takeIf { it.isDirectory }
    }
}
