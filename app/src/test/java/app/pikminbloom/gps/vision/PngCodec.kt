package app.pikminbloom.gps.vision

import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A minimal PNG reader/writer for the vision tests.
 *
 * ## Why this exists instead of `javax.imageio.ImageIO`
 * These are Android unit tests. AGP compiles the `test` source set against **`android.jar`**, which
 * acts as the bootclasspath and provides only Android's subset of the JDK. `javax.imageio` and
 * `java.awt` live in the JDK's `java.desktop` module and are simply not on that compile classpath,
 * so `import javax.imageio.ImageIO` does not resolve — even though the classes *are* present at
 * runtime, because unit tests execute on a real JVM. The alternatives were reflection (unreadable)
 * or adding a test dependency and touching `build.gradle` (out of scope for this change), so the
 * decoder is spelled out here instead. `java.util.zip` **is** in `android.jar`, which is the only
 * hard part of PNG.
 *
 * ## Scope
 * Exactly what `adb shell screencap -p` produces and what the debug dump needs:
 * 8-bit **RGB (colour type 2)** and **RGBA (colour type 6)**, non-interlaced, with all five
 * standard scanline filters. Anything else throws rather than silently decoding wrong — a wrong
 * decode would look like a detector bug.
 */
object PngCodec {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun read(file: File): RgbImage = decode(file.readBytes())

    fun decode(bytes: ByteArray): RgbImage {
        require(bytes.size > SIGNATURE.size) { "not a PNG: ${bytes.size} bytes" }
        for (i in SIGNATURE.indices) {
            require(bytes[i] == SIGNATURE[i]) { "bad PNG signature at byte $i" }
        }

        var pos = SIGNATURE.size
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = -1
        val idat = ByteArrayOutputStream()

        while (pos + 8 <= bytes.size) {
            val len = readInt(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            require(len >= 0 && dataStart + len + 4 <= bytes.size) { "truncated PNG chunk $type" }
            when (type) {
                "IHDR" -> {
                    width = readInt(bytes, dataStart)
                    height = readInt(bytes, dataStart + 4)
                    bitDepth = bytes[dataStart + 8].toInt() and 0xFF
                    colorType = bytes[dataStart + 9].toInt() and 0xFF
                    val interlace = bytes[dataStart + 12].toInt() and 0xFF
                    require(bitDepth == 8) { "only 8-bit PNGs are supported, got $bitDepth" }
                    require(colorType == 2 || colorType == 6) {
                        "only RGB(2) and RGBA(6) PNGs are supported, got colour type $colorType"
                    }
                    require(interlace == 0) { "interlaced PNGs are not supported" }
                }
                "IDAT" -> idat.write(bytes, dataStart, len)
                "IEND" -> return build(idat.toByteArray(), width, height, colorType)
            }
            pos = dataStart + len + 4
        }
        return build(idat.toByteArray(), width, height, colorType)
    }

    private fun build(compressed: ByteArray, width: Int, height: Int, colorType: Int): RgbImage {
        require(width > 0 && height > 0) { "PNG had no IHDR" }
        val channels = if (colorType == 6) 4 else 3
        val stride = width * channels
        val raw = inflate(compressed, height * (stride + 1))

        val pixels = IntArray(width * height)
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        var src = 0
        for (y in 0 until height) {
            val filter = raw[src++].toInt() and 0xFF
            System.arraycopy(raw, src, cur, 0, stride)
            src += stride
            unfilter(filter, cur, prev, channels)
            var o = y * width
            var i = 0
            while (i < stride) {
                val r = cur[i].toInt() and 0xFF
                val g = cur[i + 1].toInt() and 0xFF
                val b = cur[i + 2].toInt() and 0xFF
                pixels[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += channels
            }
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return RgbImage(width, height, pixels)
    }

    /** Applies the inverse of PNG scanline filter [type] to [cur] in place. */
    private fun unfilter(type: Int, cur: ByteArray, prev: ByteArray, bpp: Int) {
        val n = cur.size
        when (type) {
            0 -> Unit
            1 -> for (i in bpp until n) cur[i] = (cur[i] + cur[i - bpp]).toByte()
            2 -> for (i in 0 until n) cur[i] = (cur[i] + prev[i]).toByte()
            3 -> for (i in 0 until n) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                cur[i] = (cur[i] + ((a + b) / 2)).toByte()
            }
            4 -> for (i in 0 until n) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                cur[i] = (cur[i] + paeth(a, b, c)).toByte()
            }
            else -> throw IllegalArgumentException("unknown PNG filter $type")
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArray(expected)
        var off = 0
        while (off < expected && !inf.finished()) {
            val n = inf.inflate(out, off, expected - off)
            if (n == 0) {
                if (inf.needsInput() || inf.needsDictionary()) break
            }
            off += n
        }
        inf.end()
        require(off == expected) { "PNG inflated to $off bytes, expected $expected" }
        return out
    }

    // ------------------------------------------------------------------------------------------

    /** Writes [img] as an 8-bit RGB PNG (no alpha, filter type 0 on every row). */
    fun write(img: RgbImage, file: File) {
        file.parentFile?.mkdirs()
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)

        val ihdr = ByteArray(13)
        writeInt(ihdr, 0, img.width)
        writeInt(ihdr, 4, img.height)
        ihdr[8] = 8      // bit depth
        ihdr[9] = 2      // colour type: truecolour RGB
        ihdr[10] = 0     // compression
        ihdr[11] = 0     // filter
        ihdr[12] = 0     // interlace
        writeChunk(out, "IHDR", ihdr)

        val stride = img.width * 3
        val raw = ByteArray(img.height * (stride + 1))
        var o = 0
        for (y in 0 until img.height) {
            raw[o++] = 0
            var i = y * img.width
            for (x in 0 until img.width) {
                val p = img.pixels[i++]
                raw[o++] = ((p ushr 16) and 0xFF).toByte()
                raw[o++] = ((p ushr 8) and 0xFF).toByte()
                raw[o++] = (p and 0xFF).toByte()
            }
        }
        writeChunk(out, "IDAT", deflate(raw))
        writeChunk(out, "IEND", ByteArray(0))
        file.writeBytes(out.toByteArray())
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val def = Deflater(Deflater.BEST_SPEED)
        def.setInput(raw)
        def.finish()
        val out = ByteArrayOutputStream(raw.size / 2)
        val buf = ByteArray(1 shl 16)
        while (!def.finished()) {
            val n = def.deflate(buf)
            out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4)
        writeInt(len, 0, data.size)
        out.write(len)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val c = ByteArray(4)
        writeInt(c, 0, crc.value.toInt())
        out.write(c)
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun writeInt(b: ByteArray, at: Int, v: Int) {
        b[at] = ((v ushr 24) and 0xFF).toByte()
        b[at + 1] = ((v ushr 16) and 0xFF).toByte()
        b[at + 2] = ((v ushr 8) and 0xFF).toByte()
        b[at + 3] = (v and 0xFF).toByte()
    }
}

/** Tiny raster drawing helpers for the debug overlay; keeps `java.awt` out of the test source set. */
object Draw {

    fun rect(img: RgbImage, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int, thickness: Int = 3) {
        for (t in 0 until thickness) {
            hLine(img, x0, x1, y0 + t, argb)
            hLine(img, x0, x1, y1 - t, argb)
            vLine(img, x0 + t, y0, y1, argb)
            vLine(img, x1 - t, y0, y1, argb)
        }
    }

    fun cross(img: RgbImage, cx: Int, cy: Int, r: Int, argb: Int, thickness: Int = 3) {
        for (t in -(thickness / 2)..(thickness / 2)) {
            hLine(img, cx - r, cx + r, cy + t, argb)
            vLine(img, cx + t, cy - r, cy + r, argb)
        }
    }

    fun circle(img: RgbImage, cx: Int, cy: Int, r: Int, argb: Int) {
        var x = r
        var y = 0
        var err = 0
        while (x >= y) {
            plotOctants(img, cx, cy, x, y, argb)
            y++
            err += 1 + 2 * y
            if (2 * (err - x) + 1 > 0) {
                x--
                err += 1 - 2 * x
            }
        }
    }

    fun line(img: RgbImage, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
        var x = x0
        var y = y0
        val dx = Math.abs(x1 - x0)
        val dy = -Math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            plot(img, x, y, argb)
            plot(img, x + 1, y, argb)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    private fun plotOctants(img: RgbImage, cx: Int, cy: Int, x: Int, y: Int, argb: Int) {
        plot(img, cx + x, cy + y, argb); plot(img, cx + y, cy + x, argb)
        plot(img, cx - y, cy + x, argb); plot(img, cx - x, cy + y, argb)
        plot(img, cx - x, cy - y, argb); plot(img, cx - y, cy - x, argb)
        plot(img, cx + y, cy - x, argb); plot(img, cx + x, cy - y, argb)
    }

    private fun hLine(img: RgbImage, x0: Int, x1: Int, y: Int, argb: Int) {
        for (x in minOf(x0, x1)..maxOf(x0, x1)) plot(img, x, y, argb)
    }

    private fun vLine(img: RgbImage, x: Int, y0: Int, y1: Int, argb: Int) {
        for (y in minOf(y0, y1)..maxOf(y0, y1)) plot(img, x, y, argb)
    }

    private fun plot(img: RgbImage, x: Int, y: Int, argb: Int) {
        if (img.contains(x, y)) img.pixels[y * img.width + x] = argb
    }
}
