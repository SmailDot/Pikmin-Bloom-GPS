package app.pikminbloom.gps.vision

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image

/**
 * The Android side of [RgbImage]: the only file in `vision/` that imports `android.*`.
 *
 * Everything else in the package stays JVM-only so the detector can be tuned under plain JUnit; the
 * two factories here are deliberately thin so there is nothing in them worth testing on a device —
 * the stride handling they depend on lives in the pure [RgbaBytes] and is covered there.
 */

/**
 * Copies an `RGBA_8888` [Image] (as produced by an `ImageReader` behind a `VirtualDisplay`) into an
 * [RgbImage]. The image is NOT closed; the caller owns it.
 *
 * `Image.Plane.rowStride` is almost never `width * 4` on real hardware — the buffer is padded to
 * the GPU's alignment — so the row and pixel strides are honoured rather than assumed.
 */
fun RgbImage.Companion.fromImage(image: Image): RgbImage {
    require(image.format == PixelFormat.RGBA_8888) { "expected RGBA_8888, got format ${image.format}" }
    val plane = image.planes[0]
    val buffer = plane.buffer
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return RgbaBytes.toRgbImage(
        bytes = bytes,
        width = image.width,
        height = image.height,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
    )
}

/** [Bitmap.getPixels] already hands back `0xAARRGGBB` ints, exactly the packing [RgbImage] uses. */
fun RgbImage.Companion.fromBitmap(bitmap: Bitmap): RgbImage {
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    return RgbImage(w, h, px)
}
