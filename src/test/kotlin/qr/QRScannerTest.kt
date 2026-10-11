package qr

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** QRScanner construction, validation, ingestion and lifecycle (decode.test.ts L120-200 spirit). */
class QRScannerTest {
    @Test
    fun optionValidation() {
        assertThrows<IllegalArgumentException> { QRScanner(4, 4, effort = 0) }
        assertThrows<IllegalArgumentException> { QRScanner(4, 4, timeLimitMs = -1.0) }
        assertThrows<IllegalArgumentException> { QRScanner(4, 4, timeLimitMs = Double.NaN) }
        assertThrows<IllegalArgumentException> { QRScanner(4, 4, nativeLimit = -1) }
        assertThrows<IllegalArgumentException> { QRScanner(0, 4) }
        assertThrows<IllegalArgumentException> { QRScanner(4, 0) }
        assertThrows<IllegalArgumentException> { QRScanner(4097, 4) }
        QRScanner(4096, 4096, effort = Int.MAX_VALUE, timeLimitMs = Double.POSITIVE_INFINITY, nativeLimit = 0)
    }

    @Test
    fun imageValidation() {
        val s = QRScanner(4, 4)
        assertThrows<IllegalArgumentException> { s.addImage(0, 2, ByteArray(12)) }
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(13)) } // neither 3*px nor 4*px
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(4)) } // gray not inferred
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(13), PixelFormat.RGB) }
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(3), PixelFormat.GRAY) }
        assertThrows<IllegalArgumentException> { s.addImage(5, 4, ByteArray(60)) } // larger than maxSize
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(10), PixelFormat.GRAY, offset = 5, stride = 4) } // end 11 > 10
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(10), PixelFormat.GRAY, offset = 0, stride = 1) }
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(10), PixelFormat.GRAY, offset = -1, stride = 2) }
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(12), offset = 0, stride = 6) } // needs format
        s.addImage(2, 2, ByteArray(5), PixelFormat.GRAY) // planar: at least px bytes
        s.addImage(2, 2, ByteArray(10), PixelFormat.GRAY, offset = 2, stride = 4)
        s.processImage(4, 4)
        assertThrows<IllegalArgumentException> { s.processImage(5, 4) }
    }

    @Test
    fun formatInference() {
        assertEquals(PixelFormat.RGB, Image(2, 2, ByteArray(12)).format)
        assertEquals(PixelFormat.RGBA, Image(2, 2, ByteArray(16)).format)
        assertEquals(PixelFormat.GRAY, Image(2, 2, ByteArray(4)).format)
        assertEquals(PixelFormat.GRAY, Image(2, 2, ByteArray(6)).format)
        assertThrows<IllegalArgumentException> { Image(2, 2, ByteArray(3)).format }
    }

    // decode.test.ts L166-182
    @Test
    fun rgbLumaConversion() {
        val s = QRScanner(4, 4)
        val owned = s.luma
        s.addImage(2, 2, bytes(4, 8, 12, 20, 24, 28, 36, 40, 44, 52, 56, 60), PixelFormat.RGB)
        assertEquals(2, s.width)
        assertEquals(2, s.height)
        assertArrayEquals(bytes(8, 24, 40, 56), s.luma.copyOf(4))
        assertSame(owned, s.luma)
        assertSame(owned, s.layers[0].luma)
    }

    @Test
    fun rgbRgbaGrayAgree() {
        val w = 7
        val h = 5
        val gray = ByteArray(w * h) { (it * 37 + 11).toByte() }
        val rgb = ByteArray(w * h * 3)
        val rgba = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            for (c in 0 until 3) rgb[i * 3 + c] = gray[i]
            for (c in 0 until 3) rgba[i * 4 + c] = gray[i]
            rgba[i * 4 + 3] = (i * 91).toByte() // fourth byte ignored
        }
        val s = QRScanner(w, h)
        s.addImage(Image(w, h, gray))
        val a = s.luma.copyOf()
        s.addImage(Image(w, h, rgb))
        val b = s.luma.copyOf()
        s.addImage(Image(w, h, rgba))
        val c = s.luma.copyOf()
        assertArrayEquals(gray, a)
        assertArrayEquals(a, b)
        assertArrayEquals(a, c)
        // Strided RGB rows with a 2-byte pad and a 3-byte prefix.
        val stride = w * 3 + 2
        val strided = ByteArray(3 + (h - 1) * stride + w * 3)
        for (y in 0 until h) System.arraycopy(rgb, y * w * 3, strided, 3 + y * stride, w * 3)
        s.addImage(w, h, strided, PixelFormat.RGB, offset = 3, stride = stride)
        assertArrayEquals(a, s.luma)
    }

    @Test
    fun layersAndStaging() {
        val s = QRScanner(1024, 576)
        assertEquals(4, s.layers.size)
        assertEquals(listOf(1, 2, 4, 8), s.layers.map { it.scale })
        assertEquals(listOf(1024 * 576, 512 * 288, 256 * 144, 128 * 72), s.layers.map { it.luma.size })
        s.processImage(300, 200)
        assertEquals(listOf(true, true, false, false), s.layers.map { it.used })
        assertEquals(listOf(300, 150, 0, 0), s.layers.map { it.width })
        assertEquals(listOf(100, 0, 0), s.layers.drop(1).map { it.height })
        assertEquals(listOf(38, 19, 0, 0), s.layers.map { it.blockWidth })
        assertEquals(listOf(10, 5, 0, 0), s.layers.map { it.words })
        assertTrue(s.layers[0].search)
        s.nativeLimit = 199
        s.processImage(300, 200)
        assertFalse(s.layers[0].search) // native side 200 > nativeLimit
        assertTrue(s.layers[1].search)
        s.processImage(300, 127)
        assertTrue(s.layers[0].search) // shorter side < 128 always searches native
        assertFalse(s.layers[1].used)
        assertEquals(1, QRScanner(127, 4096).layers.size)
    }

    @Test
    fun cleanResets() {
        val s = QRScanner(128, 128)
        s.addImage(Image(128, 128, ByteArray(128 * 128) { (it * 7).toByte() }))
        s.ensureResized()
        s.layers.forEach { s.prepareLayer(it) }
        s.clean()
        assertEquals(0, s.width)
        assertEquals(0, s.height)
        assertTrue(s.luma.all { it == 0.toByte() })
        for (layer in s.layers) {
            assertFalse(layer.used)
            assertFalse(layer.found)
            assertEquals(0, layer.width)
            assertTrue(layer.luma.all { it == 0.toByte() })
            assertTrue(layer.bitmap.all { it == 0 })
            assertTrue(layer.blocks.all { it == 0 })
            assertTrue(layer.cuts.all { it == 0 })
        }
    }

    @Test
    fun growFindersDoublesUpToCap() {
        val s = QRScanner(100, 100)
        s.processImage(100, 100)
        val layer = s.layers[0]
        assertEquals(64, layer.state.size)
        layer.patterns[5] = 3.5
        layer.growFinders()
        assertEquals(128, layer.state.size)
        assertEquals(128 * 4, layer.patterns.size)
        assertEquals(3.5, layer.patterns[5])
        layer.growFinders() // cap: ceil(100/7)^2 = 225
        assertEquals(225, layer.state.size)
        assertThrows<IllegalStateException> { layer.growFinders() }
    }

    // decode.test.ts L1872-1896: nativeLimit searches large frames on the half layer only.
    @Test
    fun nativeLimit() {
        val text = "NATIVE LIMIT"
        fun place(scale: Int, side: Int): Image {
            val symbol = TestImages.matrixToImage(TestEncoder.encodeRaw(text, border = 4), scale)
            val data = ByteArray(side * side * 4) { 255.toByte() }
            val x0 = (side - symbol.width) shr 1
            val y0 = (side - symbol.height) shr 1
            for (y in 0 until symbol.height)
                System.arraycopy(symbol.data, y * symbol.width * 4, data, ((y0 + y) * side + x0) * 4, symbol.width * 4)
            return Image(side, side, data)
        }
        fun read(img: Image, nativeLimit: Int = Int.MAX_VALUE): String? {
            val s = QRScanner(img.width, img.height, nativeLimit = nativeLimit)
            s.addImage(img)
            return s.decode().firstOrNull()
        }
        // Four pixels per module survive the 2x2 box filter: the half layer alone decodes it.
        assertEquals(text, read(place(4, 512), 256))
        // One pixel per module needs the native search.
        assertEquals(null, read(place(1, 512), 256))
        assertEquals(text, read(place(1, 512)))
        assertEquals(text, read(place(1, 512), 512))
        // A frame too small for a half layer is always searched natively.
        assertEquals(text, read(place(1, 100), 0))
    }

    // decode-batch.test.ts L203-207: a retry set decodes what the mandatory pick misses.
    @Test
    fun effortRetriesScheduledSets() {
        val img = TestImages.readLuma("boofcv-v3/detection/curved/image022.jpg")
        val default = QRScanner(img.width, img.height)
        default.addImage(img.width, img.height, img.data, PixelFormat.GRAY)
        assertEquals(emptyList<String>(), default.decode())
        val max = QRScanner(img.width, img.height, effort = Int.MAX_VALUE, timeLimitMs = Double.POSITIVE_INFINITY)
        max.addImage(img.width, img.height, img.data, PixelFormat.GRAY)
        assertEquals(listOf("Test 03"), max.decode())
    }

    private fun symbol(text: String, scale: Int) = TestImages.matrixToImage(TestEncoder.encodeRaw(text, border = 4), scale)

    // decode-batch.test.ts L9-19: centre an RGBA image horizontally on a white canvas.
    private fun pad(img: Image, width: Int): Image {
        val data = ByteArray(width * img.height * 4) { 255.toByte() }
        val offset = (width - img.width) shr 1
        for (y in 0 until img.height)
            System.arraycopy(img.data, y * img.width * 4, data, (y * width + offset) * 4, img.width * 4)
        return Image(width, img.height, data)
    }

    // decode-batch.test.ts L85-108: positional all-mode results per frame, one scanner, misses continue.
    @Test
    fun batchPositionalResults() {
        val first = symbol("BATCH FIRST", 4)
        val last = symbol("BATCH LAST", 5)
        val blank = Image(first.width, first.height, ByteArray(first.data.size) { 255.toByte() })
        val s = QRScanner(256, 256)
        val actual = listOf(first, blank, last).map { img ->
            s.addImage(img)
            s.decode(all = true).map { it as Any? } + s.lastFailure
        }
        assertEquals(listOf(listOf("BATCH FIRST", Fail.FINDER), listOf(Fail.FINDER), listOf("BATCH LAST", Fail.FINDER)), actual)
    }

    // decode-batch.test.ts L21-46: one scanner reused across mixed frame sizes matches one-shot decodes.
    @Test
    fun reuseAcrossFrameSizes() {
        val images = listOf(
            pad(symbol("BATCH SMALL", 4), 180),
            pad(symbol("BATCH MEDIUM", 7), 260),
            pad(symbol("BATCH LARGE", 11), 420),
        )
        val s = QRScanner(512, 512)
        for (img in images + images.reversed()) {
            s.addImage(img)
            val expected = QRDecoder.decode(img)
            assertEquals(listOf(expected, Fail.FINDER), s.decode(all = true).map { it as Any? } + s.lastFailure)
            assertEquals(img.width, s.width)
            assertEquals(img.height, s.height)
        }
        assertEquals(listOf("BATCH SMALL", "BATCH MEDIUM", "BATCH LARGE"), images.map { QRDecoder.decode(it) })
    }

    // decode-batch.test.ts L127: decode before addImage, or after clean(), throws.
    @Test
    fun decodeRequiresStagedImage() {
        val img = symbol("STAGED", 4)
        val s = QRScanner(img.width, img.height)
        assertEquals("expected addImage before decode", assertThrows<IllegalStateException> { s.decode() }.message)
        s.addImage(img)
        assertEquals(listOf("STAGED"), s.decode())
        s.clean()
        assertEquals("expected addImage before decode", assertThrows<IllegalStateException> { s.decode() }.message)
        s.addImage(img)
        assertEquals(listOf("STAGED"), s.decode())
    }

    // decode.test.ts L273-286 (I420 shape): luma written straight into the owned arena.
    @Test
    fun processLumaZeroCopy() {
        val img = TestImages.matrixToGrayImage(TestEncoder.encodeRaw("ZERO COPY LUMA", border = 4), 4)
        val s = QRScanner(img.width + 40, img.height + 20)
        val owned = s.luma
        System.arraycopy(img.data, 0, s.luma, 0, img.data.size)
        s.processImage(img.width, img.height)
        assertSame(owned, s.luma)
        assertEquals(img.width, s.width)
        assertEquals(listOf("ZERO COPY LUMA"), s.decode())
    }

    // Camera Y plane: row stride > width plus a leading offset decodes like the tight copy.
    @Test
    fun grayStridedPlaneMatchesTight() {
        val img = TestImages.matrixToGrayImage(TestEncoder.encodeRaw("STRIDED Y PLANE", border = 4), 4)
        val w = img.width
        val h = img.height
        val stride = w + 64
        val offset = 32
        val plane = ByteArray(offset + h * stride) { 77 }
        for (y in 0 until h) System.arraycopy(img.data, y * w, plane, offset + y * stride, w)
        val tight = QRScanner(w, h)
        tight.addImage(w, h, img.data, PixelFormat.GRAY)
        val strided = QRScanner(w, h)
        strided.addImage(w, h, plane, PixelFormat.GRAY, offset = offset, stride = stride)
        assertArrayEquals(tight.luma, strided.luma)
        assertEquals(listOf("STRIDED Y PLANE"), tight.decode())
        assertEquals(listOf("STRIDED Y PLANE"), strided.decode())
    }

    // decode-batch.test.ts L390-436: every byte format converts into the same reused arena.
    @Test
    fun allPixelFormatsConvert() {
        val s = QRScanner(64, 64)
        val luma = s.luma
        val rgb = bytes(4, 8, 12, 20, 24, 28)
        val rgba = bytes(4, 8, 12, 99, 20, 24, 28, 77)
        val actual = ArrayList<Pair<PixelFormat, List<Int>>>()
        fun add(data: ByteArray, format: PixelFormat) {
            s.addImage(2, 1, data, format)
            actual.add(format to s.luma.copyOf(2).map { it.toInt() and 0xFF })
        }
        add(rgb, PixelFormat.RGB)
        for (f in listOf(PixelFormat.RGBA, PixelFormat.RGBX, PixelFormat.BGRA, PixelFormat.BGRX)) add(rgba, f)
        for (f in listOf(PixelFormat.I420, PixelFormat.I420A, PixelFormat.I422, PixelFormat.I444, PixelFormat.NV12))
            add(bytes(8, 24), f)
        add(bytes(0, 2, 0, 3), PixelFormat.I420P10)
        add(bytes(0, 8, 0, 12), PixelFormat.I420P12)
        val expected = PixelFormat.entries.map {
            it to if (it.bits == 8) listOf(8, 24) else listOf(128, 192)
        }
        assertEquals(expected, actual)
        assertSame(luma, s.luma)
        assertSame(PixelFormat.I420, PixelFormat.GRAY)
    }

    // decode.test.ts L182-195 (stride arena) and decode-batch.test.ts L334-341 (arena limit).
    @Test
    fun scannerStride() {
        assertEquals(48, QRScanner(4, 3, DecodeOptions(), stride = 4).luma.size)
        assertEquals(16, QRScanner(4, 4).luma.size)
        val values = bytes(4, 8, 12, 16, 20, 24, 28, 32)
        val wide = ByteArray(3 * values.size) { values[it / 3] }
        val padded = QRScanner(4, 4, DecodeOptions(), stride = 4)
        padded.addImage(4, 2, wide, PixelFormat.RGB)
        assertEquals(4, padded.width)
        assertEquals(2, padded.height)
        assertArrayEquals(values, padded.luma.copyOf(8))
        assertSame(padded.luma, padded.layers[0].luma)
        val e = assertThrows<IllegalArgumentException> { QRScanner(4096, 4096, DecodeOptions(), stride = 5) }
        assertTrue(e.message!!.contains("arena expected <= 67108864"))
        assertThrows<IllegalArgumentException> { QRScanner(4, 4, DecodeOptions(), stride = 0) }
        assertThrows<IllegalArgumentException> { QRScanner(4, 4, DecodeOptions(effort = 0)) }
    }

    // decode.test.ts L196-226: pixels written directly into the arena convert in place.
    @Test
    fun processImageInPlace() {
        val s = QRScanner(4, 4, DecodeOptions(), stride = 4)
        val owned = s.luma
        val padded = bytes(99, 99, 4, 8, 12, 0, 20, 24, 28, 0, 99, 36, 40, 44, 0, 52, 56, 60, 0, 99)
        System.arraycopy(padded, 0, s.luma, 0, padded.size)
        s.processImage(2, 2, PixelFormat.RGBA, offset = 2, stride = 9)
        assertEquals(2, s.width)
        assertEquals(2, s.height)
        assertArrayEquals(bytes(8, 24, 40, 56), s.luma.copyOf(4))
        assertSame(owned, s.luma)

        val direct = QRScanner(4, 3, DecodeOptions(), stride = 4)
        val rgba = bytes(4, 8, 12, 255, 20, 24, 28, 0, 36, 40, 44, 255, 52, 56, 60, 0)
        System.arraycopy(rgba, 0, direct.luma, 0, rgba.size)
        direct.processImage(2, 2, PixelFormat.RGBA, 0, 8)
        assertArrayEquals(bytes(8, 24, 40, 56), direct.luma.copyOf(4))
        // Default stride = width * step (tight rows of the named format).
        System.arraycopy(rgba, 0, direct.luma, 0, rgba.size)
        direct.processImage(2, 2, PixelFormat.RGBA)
        assertArrayEquals(bytes(8, 24, 40, 56), direct.luma.copyOf(4))
        // 10-bit samples in place.
        System.arraycopy(bytes(0, 2, 0, 3, 0, 1, 255, 3), 0, direct.luma, 0, 8)
        direct.processImage(2, 2, PixelFormat.I420P10)
        assertArrayEquals(bytes(128, 192, 64, 255), direct.luma.copyOf(4))
        assertThrows<IllegalArgumentException> { direct.processImage(2, 2, PixelFormat.RGBA, offset = 40, stride = 8) }
    }

    // decode.ts L586-596: planar formats accept oversize buffers, packed formats need exact sizes.
    @Test
    fun formatSizeValidation() {
        val s = QRScanner(4, 4)
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(7), PixelFormat.I420P10) }
        s.addImage(2, 2, ByteArray(9), PixelFormat.I420P12)
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(13), PixelFormat.RGB) }
        assertThrows<IllegalArgumentException> { s.addImage(2, 2, ByteArray(17), PixelFormat.BGRX) }
        assertEquals(PixelFormat.RGBA, QRScanner.validateImage(2, 2, ByteArray(16), null, 0, 0, 4, 4))
        assertEquals(PixelFormat.NV12, QRScanner.validateImage(2, 2, ByteArray(6), PixelFormat.NV12, 0, 0, 4, 4))
        assertThrows<IllegalArgumentException> { QRScanner.validateImage(2, 2, ByteArray(4), null, 0, 0, 4, 4) }
        // options.format is the addImage default (JS `format = this.opts.format`).
        val gray = QRScanner(4, 4, DecodeOptions(format = PixelFormat.I420))
        gray.addImage(2, 2, bytes(1, 2, 3, 4))
        assertArrayEquals(bytes(1, 2, 3, 4), gray.luma.copyOf(4))
        gray.addImage(Image(2, 2, bytes(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)))
        assertArrayEquals(bytes(5, 6, 7, 8), gray.luma.copyOf(4))
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
