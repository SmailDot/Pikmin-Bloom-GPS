package app.pikminbloom.gps.vision

/**
 * A plain ARGB raster, row-major, 8 bits per channel.
 *
 * Deliberately free of `android.*`: everything in `vision/` must compile and run under plain JUnit
 * on the JVM so the detector can be tuned against real screenshots without a device or emulator.
 *
 * The Android factories live in `AndroidImages.kt` (`RgbImage.fromImage(image)` for the
 * `ImageReader` frames behind a `VirtualDisplay`, honouring the padded row stride, and
 * `RgbImage.fromBitmap(bitmap)`), the only file in this package that imports `android.*`.
 * The JVM test side decodes PNGs through `PngCodec`. All of them produce the identical
 * `0xAARRGGBB` packing this class assumes.
 */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        require(pixels.size == width * height) {
            "pixel array is ${pixels.size}, expected ${width * height} for ${width}x$height"
        }
    }

    /** Packed `0xAARRGGBB` at ([x], [y]). No bounds check on the hot path; callers clamp. */
    fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Packed pixel, or [fallback] when ([x], [y]) is outside the raster. */
    fun getOrElse(x: Int, y: Int, fallback: Int = 0): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) fallback else pixels[y * width + x]

    fun red(x: Int, y: Int): Int = (get(x, y) ushr 16) and 0xFF

    fun green(x: Int, y: Int): Int = (get(x, y) ushr 8) and 0xFF

    fun blue(x: Int, y: Int): Int = get(x, y) and 0xFF

    fun contains(x: Int, y: Int): Boolean = x >= 0 && y >= 0 && x < width && y < height

    /**
     * Copy of the rectangle ([x], [y], [w], [h]). The rectangle is clamped to the image; an empty
     * intersection throws, because a zero-sized [RgbImage] is not representable.
     */
    fun sub(x: Int, y: Int, w: Int, h: Int): RgbImage {
        val x0 = x.coerceIn(0, width - 1)
        val y0 = y.coerceIn(0, height - 1)
        val x1 = (x + w).coerceIn(x0 + 1, width)
        val y1 = (y + h).coerceIn(y0 + 1, height)
        val sw = x1 - x0
        val sh = y1 - y0
        val out = IntArray(sw * sh)
        for (row in 0 until sh) {
            System.arraycopy(pixels, (y0 + row) * width + x0, out, row * sw, sw)
        }
        return RgbImage(sw, sh, out)
    }

    /**
     * Nearest-neighbour downscale by an integer [factor]. Detection on a 1220x2712 frame is
     * ~3.3 Mpx; halving once costs almost no accuracy for blobs of a few hundred pixels and makes
     * the connected-component pass roughly four times cheaper. Kept here so both the tests and the
     * eventual service can use exactly the same reduction.
     */
    fun downscale(factor: Int): RgbImage {
        require(factor >= 1) { "factor must be >= 1, was $factor" }
        if (factor == 1) return this
        val w = width / factor
        val h = height / factor
        require(w > 0 && h > 0) { "downscale by $factor collapses ${width}x$height" }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val srcRow = (y * factor) * width
            val dstRow = y * w
            for (x in 0 until w) {
                out[dstRow + x] = pixels[srcRow + x * factor]
            }
        }
        return RgbImage(w, h, out)
    }

    companion object {
        /** All-opaque-black image of the given size, handy for synthetic tests. */
        fun blank(width: Int, height: Int, argb: Int = 0xFF000000.toInt()): RgbImage =
            RgbImage(width, height, IntArray(width * height) { argb })
    }
}

/**
 * Colour-space helpers shared by the detector.
 *
 * Hue is in `[0, 360)` degrees, saturation and value in `[0, 1]`. This is the plain HSV hexcone
 * (a.k.a. HSB), matching `java.awt.Color.RGBtoHSB` and Android's `Color.colorToHSV` apart from
 * hue being scaled to degrees here.
 */
object ColorMath {

    /** Hue in degrees `[0, 360)`; 0 for fully desaturated pixels. */
    fun hue(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        if (d == 0) return 0.0
        val h = when (max) {
            r -> 60.0 * (((g - b).toDouble() / d) % 6.0)
            g -> 60.0 * ((b - r).toDouble() / d + 2.0)
            else -> 60.0 * ((r - g).toDouble() / d + 4.0)
        }
        return if (h < 0.0) h + 360.0 else h
    }

    /** Saturation in `[0, 1]`. */
    fun saturation(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        if (max == 0) return 0.0
        val min = minOf(r, g, b)
        return (max - min).toDouble() / max.toDouble()
    }

    /** Value (max channel) in `[0, 1]`. */
    fun value(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return maxOf(r, g, b).toDouble() / 255.0
    }

    /** All three at once; avoids re-unpacking the pixel three times in the inner loop. */
    fun toHsv(argb: Int, out: DoubleArray) {
        require(out.size >= 3) { "out must hold 3 doubles" }
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        out[0] = if (d == 0) 0.0 else {
            val h = when (max) {
                r -> 60.0 * (((g - b).toDouble() / d) % 6.0)
                g -> 60.0 * ((b - r).toDouble() / d + 2.0)
                else -> 60.0 * ((r - g).toDouble() / d + 4.0)
            }
            if (h < 0.0) h + 360.0 else h
        }
        out[1] = if (max == 0) 0.0 else d.toDouble() / max.toDouble()
        out[2] = max.toDouble() / 255.0
    }

    /**
     * Smallest absolute difference between two hues, in degrees `[0, 180]`.
     * Hue is circular, so 350 and 10 are 20 apart, not 340.
     */
    fun hueDistance(a: Double, b: Double): Double {
        var d = Math.abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }
}
