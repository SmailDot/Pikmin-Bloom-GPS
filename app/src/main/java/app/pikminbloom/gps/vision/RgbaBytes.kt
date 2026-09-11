package app.pikminbloom.gps.vision

/**
 * Packs the raw bytes of an `RGBA_8888` buffer, as handed out by `ImageReader` / `Image.Plane`,
 * into the `0xAARRGGBB` ints [RgbImage] expects.
 *
 * Pure Kotlin on purpose: the stride arithmetic is exactly the kind of thing that is easy to get
 * subtly wrong (every row of a GPU-produced buffer is padded to an alignment boundary, so
 * `rowStride > width * pixelStride` is the normal case, not the exception), and a JVM test with a
 * deliberately padded buffer is the cheapest way to prove it right.
 */
object RgbaBytes {

    /**
     * @param bytes the plane's bytes, at least `(height - 1) * rowStride + width * pixelStride` long.
     * @param rowStride bytes from the start of one row to the start of the next (>= width * pixelStride).
     * @param pixelStride bytes between two adjacent pixels of a row (4 for a tightly packed RGBA_8888).
     */
    fun toRgbImage(bytes: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int): RgbImage {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        require(pixelStride >= 4) { "pixelStride $pixelStride is too small for RGBA_8888" }
        require(rowStride >= width * pixelStride) { "rowStride $rowStride < width $width * pixelStride $pixelStride" }
        val needed = (height - 1).toLong() * rowStride + width.toLong() * pixelStride
        require(bytes.size >= needed) { "buffer holds ${bytes.size} bytes, need $needed" }

        val out = IntArray(width * height)
        for (y in 0 until height) {
            var src = y * rowStride
            val dstRow = y * width
            for (x in 0 until width) {
                val r = bytes[src].toInt() and 0xFF
                val g = bytes[src + 1].toInt() and 0xFF
                val b = bytes[src + 2].toInt() and 0xFF
                val a = bytes[src + 3].toInt() and 0xFF
                out[dstRow + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                src += pixelStride
            }
        }
        return RgbImage(width, height, out)
    }
}
