package qr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class QRDecoderTest {

    @Test
    fun `decode should throw for empty image`() {
        assertThrows<IllegalArgumentException> {
            QRDecoder.decode(0, 0, byteArrayOf())
        }
    }

    @Test
    fun `decode should throw FinderNotFoundException for too small image`() {
        // A frame below the smallest symbol size has no finder triple (JS fails with `finder`).
        val width = 10
        val height = 10
        val data = ByteArray(width * height * 4) { 255.toByte() }

        assertThrows<FinderNotFoundException> {
            QRDecoder.decode(width, height, data)
        }
    }

    @Test
    fun `Image constructor should validate dimensions`() {
        assertThrows<IllegalArgumentException> {
            Image(-1, 10, ByteArray(100))
        }
        assertThrows<IllegalArgumentException> {
            Image(10, 0, ByteArray(100))
        }
        assertThrows<IllegalArgumentException> {
            Image(10, 10, byteArrayOf())
        }
    }

    @Test
    fun `Image bytesPerPixel should detect RGB and RGBA`() {
        val rgbImage = Image(10, 10, ByteArray(10 * 10 * 3))
        assertEquals(3, rgbImage.bytesPerPixel)

        val rgbaImage = Image(10, 10, ByteArray(10 * 10 * 4))
        assertEquals(4, rgbaImage.bytesPerPixel)
    }

    @Test
    fun `Image bytesPerPixel should throw for invalid format`() {
        // Shorter than one byte per pixel is invalid; oversize non-3/4 bpp buffers are planar gray (JS L589-596).
        val invalidImage = Image(10, 10, ByteArray(10 * 10 - 1))
        assertThrows<IllegalArgumentException> {
            invalidImage.bytesPerPixel
        }
        assertEquals(1, Image(10, 10, ByteArray(10 * 10 * 2)).bytesPerPixel)
    }

    // Helper to create a simple test QR code bitmap as an Image
    // This creates a minimal QR code pattern for testing
    private fun createTestQRImage(): Image {
        // This is a simplified version - real tests would use actual QR code images
        // For now, we test that the pipeline doesn't crash on valid input dimensions
        val size = 100
        val data = ByteArray(size * size * 4) { 255.toByte() }  // White background
        return Image(size, size, data)
    }

    @Test
    fun `decode should throw FinderNotFoundException for blank image`() {
        val image = createTestQRImage()
        assertThrows<FinderNotFoundException> {
            QRDecoder.decode(image)
        }
    }

    private fun symbol(text: String, version: Int? = null) =
        TestEncoder.encodeRaw(text, version = version, ecc = ErrorCorrection.LOW, mask = 0, border = 4)

    @Test
    fun `facade decodes gray, RGB and RGBA rasters`() {
        val raw = symbol("FACADE FORMATS")
        assertEquals("FACADE FORMATS", QRDecoder.decode(TestImages.matrixToImage(raw, 4)))
        assertEquals("FACADE FORMATS", QRDecoder.decode(TestImages.matrixToImage(raw, 4, rgb = true)))
        val gray = TestImages.matrixToGrayImage(raw, 4)
        assertEquals(PixelFormat.GRAY, gray.format)
        assertEquals("FACADE FORMATS", QRDecoder.decode(gray))
        assertEquals("FACADE FORMATS", QRDecoder.decode(gray.width, gray.height, gray.data))
    }

    // Version words of another version, or beyond BCH radius 3 in both copies: JS fails with `version`.
    @Test
    fun `version 7 with destroyed version info throws InvalidVersionException`() {
        val version = 7
        val size = 17 + 4 * version
        for (word in listOf(0, QRInfo.versionBits(version + 1))) {
            val raw = symbol("VERSION INFO GONE", version)
            for (copy in 0..1)
                for (i in 0 until 18) {
                    val x = size - 11 + (i % 3)
                    val y = i / 3
                    raw[4 + if (copy != 0) x else y][4 + if (copy != 0) y else x] = ((word shr i) and 1) != 0
                }
            val e = assertThrows<InvalidVersionException> { QRDecoder.decode(TestImages.matrixToImage(raw, 4)) }
            assertEquals("version", e.message)
        }
    }

    // Both format copies beyond BCH radius 3: the format miss is followed by the inverted-projection
    // retry, whose timing check ends the scan, so JS reports `timing` (verified against qr v0.7.2;
    // no test image ends on `format` either). The facade keeps the JS tag as a plain QRDecodingException.
    @Test
    fun `destroyed format info reports the JS terminal tag`() {
        val raw = symbol("FORMAT GONE", 1)
        val size = raw.size - 8
        for (copy in 0..1)
            for (i in 0 until 15) {
                val x = if (copy == 0) (if (i < 9) (if (i == 8) 7 else 8) else 14 - i) else (if (i < 8) size - 1 - i else 8)
                val y = if (copy == 0) (if (i < 6) i else if (i < 8) i + 1 else 8) else (if (i < 8) 8 else size - 15 + i)
                raw[4 + y][4 + x] = false
            }
        val e = assertThrows<QRDecodingException> { QRDecoder.decode(TestImages.matrixToImage(raw, 4)) }
        assertEquals(QRDecodingException::class, e::class)
        assertEquals("timing", e.message)
    }

    // decode-batch.test.ts L48-83: the damaged 'A' symbol fails with `timing`.
    @Test
    fun `other failure tags throw QRDecodingException with the tag as message`() {
        val raw = symbol("A", 1)
        for (y in 12 until 16) for (x in 12 until 16) raw[y][x] = !raw[y][x]
        val e = assertThrows<QRDecodingException> { QRDecoder.decode(TestImages.matrixToImage(raw, 4)) }
        assertEquals(QRDecodingException::class, e::class)
        assertEquals("timing", e.message)
        val finder = assertThrows<FinderNotFoundException> { QRDecoder.decode(createTestQRImage()) }
        assertEquals("finder", finder.message)
    }

    @Test
    fun `cached scanner is rebuilt when the frame size changes`() {
        // Hold the facade lock (reentrant) so concurrently running test classes cannot swap the scanner.
        synchronized(QRDecoder) {
            val raw = symbol("GROW THE SCANNER")
            val field = QRDecoder::class.java.getDeclaredField("scanner").apply { isAccessible = true }
            assertEquals("GROW THE SCANNER", QRDecoder.decode(TestImages.matrixToImage(raw, 2)))
            val small = field.get(QRDecoder) as QRScanner
            assertEquals("GROW THE SCANNER", QRDecoder.decode(TestImages.matrixToImage(raw, 2)))
            assertSame(small, field.get(QRDecoder), "same size reuses the scanner")
            val big = TestImages.matrixToImage(raw, 41) // v1: 29 modules x 41 px = 1189 px per side
            assertEquals("GROW THE SCANNER", QRDecoder.decode(big))
            val scanner = field.get(QRDecoder) as QRScanner
            assertNotSame(small, scanner)
            assertEquals(big.width, scanner.maxWidth)
            assertEquals(big.height, scanner.maxHeight)
            assertEquals("GROW THE SCANNER", QRDecoder.decode(TestImages.matrixToImage(raw, 2)))
            assertNotSame(scanner, field.get(QRDecoder), "a smaller size rebuilds too")
        }
    }

    // decode.test.ts L228-288 (plus RGB): every explicit pixel format through decode(image, options).
    @Test
    fun `decode with options converts every explicit pixel format`() {
        val rgba = TestImages.matrixToImage(TestEncoder.encodeRaw("PIXEL FORMATS", border = 4), 4)
        val width = rgba.width
        val height = rgba.height
        val pixels = width * height
        val luma = IntArray(pixels) { rgba.data[4 * it].toInt() and 0xFF }
        val halfWidth = (width + 1) / 2
        val halfHeight = (height + 1) / 2
        fun samples(f: PixelFormat) = when (f) {
            PixelFormat.I420, PixelFormat.I420P10, PixelFormat.I420P12, PixelFormat.NV12 -> pixels + 2 * halfWidth * halfHeight
            PixelFormat.I420A -> 2 * pixels + 2 * halfWidth * halfHeight
            PixelFormat.I422 -> pixels + 2 * halfWidth * height
            PixelFormat.I444 -> 3 * pixels
            else -> error("packed")
        }
        fun planar(f: PixelFormat): ByteArray {
            val bits = f.bits
            val data = ByteArray(samples(f) * (if (bits == 8) 1 else 2)) { 128.toByte() }
            if (bits == 8) for (i in 0 until pixels) data[i] = luma[i].toByte()
            else for (i in 0 until pixels) {
                val sample = luma[i] shl (bits - 8)
                data[2 * i] = (sample and 255).toByte()
                data[2 * i + 1] = (sample ushr 8).toByte()
            }
            return data
        }
        fun packed(f: PixelFormat): ByteArray {
            val step = f.step
            val data = ByteArray(step * pixels)
            for (i in 0 until pixels) {
                data[step * i] = luma[i].toByte()
                data[step * i + 1] = luma[i].toByte()
                data[step * i + 2] = luma[i].toByte()
                if (step == 4) data[step * i + 3] = (i and 255).toByte()
            }
            return data
        }
        val formats = PixelFormat.entries
        assertEquals(12, formats.size)
        assertEquals(
            formats.map { "PIXEL FORMATS" },
            formats.map { f ->
                val data = if (f.step <= 2) planar(f) else packed(f)
                QRDecoder.decode(Image(width, height, data), DecodeOptions(format = f))
            },
        )
    }

    @Test
    fun `decode with options leaves the cached scanner untouched`() {
        synchronized(QRDecoder) {
            val img = TestImages.matrixToImage(symbol("OWN SCANNER"), 3)
            val field = QRDecoder::class.java.getDeclaredField("scanner").apply { isAccessible = true }
            assertEquals("OWN SCANNER", QRDecoder.decode(img))
            val cached = field.get(QRDecoder)
            assertNotNull(cached)
            assertEquals("OWN SCANNER", QRDecoder.decode(img, DecodeOptions()))
            val other = TestImages.matrixToImage(symbol("OWN SCANNER"), 5)
            assertEquals("OWN SCANNER", QRDecoder.decode(other, DecodeOptions(effort = 2)))
            assertSame(cached, field.get(QRDecoder))
        }
    }

    @Test
    fun `decode with options maps failures and validates inputs`() {
        val blank = Image(64, 64, ByteArray(64 * 64) { 255.toByte() })
        assertThrows<FinderNotFoundException> { QRDecoder.decode(blank, DecodeOptions()) }
        assertThrows<IllegalArgumentException> { QRDecoder.decode(blank, DecodeOptions(effort = 0)) }
        assertThrows<IllegalArgumentException> {
            QRDecoder.decode(Image(4, 4, ByteArray(16)), DecodeOptions(format = PixelFormat.RGBA))
        }
    }

    @Test
    fun `a callback may re-enter the facade`() {
        val img = TestImages.matrixToImage(symbol("OUTER CALL"), 4)
        val inner = TestImages.matrixToImage(symbol("INNER CALL"), 3)
        val seen = ArrayList<String>()
        val text = QRDecoder.decode(img, DecodeOptions(imageOnResult = {
            seen.add(QRDecoder.decode(inner))
            seen.add(QRDecoder.decode(inner, DecodeOptions()))
        }))
        assertEquals("OUTER CALL", text)
        assertEquals(listOf("INNER CALL", "INNER CALL"), seen)
    }

    @Test
    fun `concurrent facade calls do not corrupt results`() {
        val texts = listOf("THREAD ONE", "THREAD TWO PAYLOAD", "THREAD THREE 3333")
        val images = texts.mapIndexed { i, t -> TestImages.matrixToImage(symbol(t), 3 + i) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(texts.size)
        try {
            val futures = images.mapIndexed { i, img ->
                pool.submit<List<String>> { List(20) { QRDecoder.decode(img) }.filter { it != texts[i] } }
            }
            for (f in futures) assertEquals(emptyList<String>(), f.get())
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `ErrorCorrection should have correct codes`() {
        assertEquals(0b01, ErrorCorrection.LOW.code)
        assertEquals(0b00, ErrorCorrection.MEDIUM.code)
        assertEquals(0b11, ErrorCorrection.QUARTILE.code)
        assertEquals(0b10, ErrorCorrection.HIGH.code)
    }

    @Test
    fun `ErrorCorrection fromCode should return correct enum`() {
        assertEquals(ErrorCorrection.LOW, ErrorCorrection.fromCode(0b01))
        assertEquals(ErrorCorrection.MEDIUM, ErrorCorrection.fromCode(0b00))
        assertEquals(ErrorCorrection.QUARTILE, ErrorCorrection.fromCode(0b11))
        assertEquals(ErrorCorrection.HIGH, ErrorCorrection.fromCode(0b10))
    }
}
