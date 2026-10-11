package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import qr.TestEncoder.Encoding

/** Synthetic decoder tests ported from qr-paulmillr test/decode.test.ts (line numbers cited). */
class SyntheticTest {
    private val L = ErrorCorrection.LOW
    private val M = ErrorCorrection.MEDIUM
    private val Q = ErrorCorrection.QUARTILE
    private val H = ErrorCorrection.HIGH

    private fun read(img: Image): String = QRDecoder.decode(img)

    private fun rgba(width: Int, height: Int, dark: (Int, Int) -> Boolean): Image {
        val data = ByteArray(width * height * 4)
        for (y in 0 until height)
            for (x in 0 until width) {
                val v = (if (dark(x, y)) 0 else 255).toByte()
                val pos = 4 * (y * width + x)
                data[pos] = v
                data[pos + 1] = v
                data[pos + 2] = v
                data[pos + 3] = 255.toByte()
            }
        return Image(width, height, data)
    }

    private fun gray(img: Image): Image =
        Image(img.width, img.height, ByteArray(img.width * img.height) { img.data[4 * it] })

    // L927-958
    @Test
    fun `roundtrip across versions, ecc, modes and masks (RGBA and RGB)`() {
        val payloads = mapOf(
            Encoding.NUMERIC to "0123456789012345678901234567890123456789",
            Encoding.ALPHANUMERIC to "HELLO WORLD +-*/: \$%.123456789",
            Encoding.BYTE to "vita nuova — 🌒🌓",
        )
        val shapes = listOf(2 to L, 3 to M, 5 to Q, 7 to H, 10 to M)
        for ((enc, text) in payloads)
            for ((version, ecc) in shapes) {
                val raw = TestEncoder.encodeRaw(text, version = version, ecc = ecc, border = 4, encoding = enc)
                assertEquals(text, read(TestImages.matrixToImage(raw, 4)), "$enc v$version $ecc")
                assertEquals(text, read(TestImages.matrixToImage(raw, 4, rgb = true)), "rgb $enc v$version $ecc")
                assertEquals(text, read(TestImages.matrixToGrayImage(raw, 4)), "gray $enc v$version $ecc")
            }
        for (mask in listOf(0, 3, 7)) {
            val text = payloads.getValue(Encoding.ALPHANUMERIC)
            val raw = TestEncoder.encodeRaw(text, ecc = M, mask = mask, border = 4)
            assertEquals(text, read(TestImages.matrixToImage(raw, 4)), "mask $mask")
            assertEquals(text, read(TestImages.matrixToImage(raw, 4, rgb = true)), "rgb mask $mask")
        }
    }

    // L960-976
    @Test
    fun `all 40 versions x 4 ECC levels decode at scale 3`() {
        for (version in 1..40)
            for (ecc in ErrorCorrection.entries) {
                val text = "A".repeat(minOf(10 + version, 60))
                val raw = try {
                    TestEncoder.encodeRaw(text, version = version, ecc = ecc, border = 4)
                } catch (e: IllegalArgumentException) {
                    continue // capacity too small for this text/version/ecc combo
                }
                assertEquals(text, read(TestImages.matrixToImage(raw, 3)), "v$version/$ecc")
            }
    }

    // L978-989
    @Test
    fun `Reed-Solomon corrects damaged data modules`() {
        val text = "DAMAGE TEST 123"
        val raw = TestEncoder.encodeRaw(text, version = 2, ecc = H, border = 4)
        for (y in 26 until 28) for (x in 22 until 27) raw[y][x] = !raw[y][x]
        assertEquals(text, read(TestImages.matrixToImage(raw, 4)))
    }

    // L445-457 (JS passes effort Infinity; the mandatory pass already decodes it)
    @Test
    fun `clean one-pixel-per-module symbol`() {
        val raw = TestEncoder.encodeRaw("ONE PIXEL", version = 4, ecc = L, mask = 0, border = 4)
        assertEquals("ONE PIXEL", read(TestImages.matrixToImage(raw, 1)))
    }

    // L445-457 with the JS options: effort Infinity, timeLimit Infinity.
    @Test
    fun `clean one-pixel-per-module symbol at max effort`() {
        val raw = TestEncoder.encodeRaw("ONE PIXEL", version = 4, ecc = L, mask = 0, border = 4)
        assertEquals(listOf("ONE PIXEL"), maxEffort().also { it.addImage(TestImages.matrixToImage(raw, 1)) }.decode())
    }

    // L416-443: a sharp 2 px/module symbol in a 3000x3000 canvas exists only at native resolution.
    @Test
    fun `max effort escalates to native resolution`() {
        val raw = TestEncoder.encodeRaw("NATIVE ONLY", version = 2, border = 4)
        val qr = TestImages.matrixToImage(raw, 2)
        val w = 3000
        val h = 3000
        val data = ByteArray(w * h * 4) { 255.toByte() }
        for (y in 0 until qr.height)
            for (x in 0 until qr.width) {
                val s = (y * qr.width + x) * 4
                val t = ((y + 1200) * w + (x + 1400)) * 4
                data[t] = qr.data[s]
                data[t + 1] = qr.data[s + 1]
                data[t + 2] = qr.data[s + 2]
                data[t + 3] = 255.toByte()
            }
        val scanner = QRScanner(w, h, effort = Int.MAX_VALUE, timeLimitMs = Double.POSITIVE_INFINITY)
        scanner.addImage(Image(w, h, data))
        assertEquals(listOf("NATIVE ONLY"), scanner.decode())
        // The strict-pass guarantee needs no retry budget: clean synthetics decode with defaults too.
        assertEquals("NATIVE ONLY", read(qr))
    }

    // decode-batch.test.ts L203-211 (synthetic half): timeLimit 0 still runs the mandatory round.
    @Test
    fun `effort infinity with timeLimit 0 still decodes the mandatory pick`() {
        val img = TestImages.matrixToImage(TestEncoder.encodeRaw("EFFORT MANDATORY", border = 4), 4)
        val scanner = QRScanner(img.width, img.height, effort = Int.MAX_VALUE, timeLimitMs = 0.0)
        scanner.addImage(img)
        assertEquals(listOf("EFFORT MANDATORY"), scanner.decode())
    }

    // decode-batch.test.ts L150-201: three symbols side by side; every one decodes once, in JS order.
    @Test
    fun `decodes every QR once without rediscovering it across pyramid layers`() {
        val texts = listOf("MULTI ONE", "MULTI TWO", "MULTI ONE")
        val symbols = texts.map { TestImages.matrixToImage(TestEncoder.encodeRaw(it, border = 4), 4) }
        val gap = 32
        val width = symbols.sumOf { it.width } + gap * (symbols.size - 1)
        val height = symbols[0].height
        val data = ByteArray(width * height * 4) { 255.toByte() }
        var offset = 0
        for (symbol in symbols) {
            for (y in 0 until height)
                System.arraycopy(symbol.data, y * symbol.width * 4, data, (y * width + offset) * 4, symbol.width * 4)
            offset += symbol.width + gap
        }
        val combined = Image(width, height, data)
        val scanner = QRScanner(width, height)
        scanner.addImage(combined)
        assertEquals(texts, scanner.decode(all = true))
        assertEquals(Fail.FINDER, scanner.lastFailure)
        // Exclusion persists across decode() calls on the same staged image.
        scanner.addImage(combined)
        assertEquals(texts, List(3) { scanner.decode().single() })
        assertEquals(emptyList<String>(), scanner.decode())
        assertEquals(Fail.FINDER, scanner.lastFailure)
        // A new staged image resets the exclusion state.
        scanner.addImage(symbols[0])
        assertEquals(listOf(texts[0]), scanner.decode(all = true))
        assertEquals(Fail.FINDER, scanner.lastFailure)
        assertEquals(texts[0], read(combined))
        val blank = QRScanner(width, height)
        blank.addImage(Image(width, height, ByteArray(data.size) { 255.toByte() }))
        assertEquals(emptyList<String>(), blank.decode(all = true))
        assertEquals(Fail.FINDER, blank.lastFailure)
    }

    private fun maxEffort(size: Int = 512) =
        QRScanner(size, size, effort = Int.MAX_VALUE, timeLimitMs = Double.POSITIVE_INFINITY)

    // L459-483 (JS passes effort Infinity; the mandatory pass already decodes it)
    @Test
    fun `nearest-neighbour resize to non-integer module widths`() {
        val raw = TestEncoder.encodeRaw("A", version = 1, ecc = L, mask = 0, border = 4)
        val side = 67
        val img = rgba(side, side) { x, y -> raw[(y * raw.size) / side][(x * raw.size) / side] }
        assertEquals("A", read(img))
    }

    // L485-506
    @Test
    fun `horizontally stretched symbol`() {
        val raw = TestEncoder.encodeRaw("ANISO", version = 1, ecc = L, mask = 0, border = 4)
        val scaleX = 12
        val scaleY = 8
        val img = rgba(raw[0].size * scaleX, raw.size * scaleY) { x, y -> raw[y / scaleY][x / scaleX] }
        assertEquals("ANISO", read(img))
    }

    // L508-515
    @Test
    fun `limited timing-pattern damage`() {
        val border = 4
        val raw = TestEncoder.encodeRaw("A", version = 1, ecc = L, mask = 0, border = border)
        for (x in 8..10) raw[border + 6][border + x] = !raw[border + 6][border + x]
        assertEquals("A", read(TestImages.matrixToImage(raw, 4)))
    }

    // L665-736 (result only; detection points are not ported)
    @Test
    fun `warped version 7 symbol through alignment tiles`() {
        val payload = "TILED PROJECTION ".repeat(5)
        val version = 7
        val border = 4
        val scale = 8
        val raw = TestEncoder.encodeRaw(payload, version = version, border = border, ecc = L, mask = 0)
        val modules = 17 + 4 * version
        val width = raw[0].size * scale
        val height = raw.size * scale
        val data = ByteArray(width * height)
        for (py in 0 until height)
            for (px in 0 until width) {
                val x = (px + 0.5) / scale - border
                val y = (py + 0.5) / scale - border
                val dx = 0.75 * Math.sin((Math.PI * x) / modules) * Math.sin((Math.PI * y) / modules)
                val sx = Math.floor(x - dx).toInt() + border
                val sy = Math.floor(y).toInt() + border
                val dark = sy in raw.indices && sx in raw[sy].indices && raw[sy][sx]
                data[py * width + px] = (if (dark) 0 else 255).toByte()
            }
        assertEquals(payload, read(Image(width, height, data)))
    }

    // L738-750
    @Test
    fun `falls back from a damaged alignment pattern`() {
        val border = 4
        val raw = TestEncoder.encodeRaw("A", version = 2, ecc = H, mask = 0, border = border)
        raw[border + 18][border + 18] = !raw[border + 18][border + 18]
        assertEquals("A", read(TestImages.matrixToImage(raw, 4)))
    }

    // L752-764
    @Test
    fun `resists one inflated finder-size estimate`() {
        val border = 4
        val raw = TestEncoder.encodeRaw("A", version = 24, ecc = H, mask = 0, border = border)
        raw[border + 2][border + 5] = !raw[border + 2][border + 5]
        assertEquals("A", read(TestImages.matrixToImage(raw, 4)))
    }

    // L766-805
    @Test
    fun `tries an intact format copy when the redundant words conflict`() {
        val border = 4
        val payload = "format byte"
        val correct = QRInfo.formatBits(Q, 7)
        val wrong = QRInfo.formatBits(L, 0)
        fun conflict(wrongCopy: Int, damagedCorrect: Int = correct): Image {
            val raw = TestEncoder.encodeRaw(payload, version = 1, ecc = Q, mask = 7, border = border)
            val size = raw.size - 2 * border
            fun write(copy: Int, bits: Int) {
                for (i in 0 until 15) {
                    val x: Int
                    val y: Int
                    if (copy == 0) {
                        x = if (i < 9) (if (i == 8) 7 else 8) else 14 - i
                        y = if (i < 6) i else if (i < 8) i + 1 else 8
                    } else {
                        x = if (i < 8) size - 1 - i else 8
                        y = if (i < 8) 8 else size - 15 + i
                    }
                    raw[border + y][border + x] = ((bits shr i) and 1) != 0
                }
            }
            // Each physical copy is BCH-corrected independently.
            write(wrongCopy, wrong)
            write(if (wrongCopy != 0) 0 else 1, damagedCorrect)
            return TestImages.matrixToImage(raw, 4)
        }
        val images = listOf(conflict(0), conflict(1), conflict(0, correct xor 1 xor (1 shl 7) xor (1 shl 14)))
        for (image in images) assertEquals(payload, read(image))

        val sentinel = RuntimeException("format callback sentinel")
        var calls = 0
        val img = images[0]
        val scanner = QRScanner(img.width, img.height, textDecoder = { _, _ ->
            calls++
            throw sentinel
        })
        scanner.addImage(img)
        val thrown = assertThrows<RuntimeException> { scanner.decode() }
        assertSame(sentinel, thrown)
        assertEquals(1, calls)
    }

    // L817-824
    @Test
    fun `rejects a byte segment whose declared length exceeds its data`() {
        val words = intArrayOf(
            79, 240, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 24, 229, 147, 129, 112, 149, 181,
        )
        assertThrows<QRDecodingException> { read(TestImages.matrixToImage(TestEncoder.symbolMatrix(1, L, words), 4)) }
    }

    // L826-870
    @Test
    fun `rejects out-of-range numeric and alphanumeric groups`() {
        val streams = mapOf(
            "numeric" to intArrayOf(
                16, 15, 232, 0, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 250, 119,
                88, 124, 205, 218, 88,
            ),
            "alphanumeric" to intArrayOf(
                32, 23, 233, 0, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 64, 102,
                161, 90, 11, 93, 68,
            ),
            "two-digit numeric" to intArrayOf(
                16, 11, 32, 0, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 199, 62,
                84, 61, 211, 36, 77,
            ),
            "one-digit numeric" to intArrayOf(
                16, 6, 128, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 233, 187,
                201, 36, 145, 187, 3,
            ),
            "one-character alphanumeric" to intArrayOf(
                32, 13, 160, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 227, 12,
                130, 229, 237, 161, 73,
            ),
        )
        for ((mode, words) in streams) {
            val img = TestImages.matrixToImage(TestEncoder.symbolMatrix(1, L, words), 4)
            assertThrows<QRDecodingException>(mode) { read(img) }
        }
    }

    // L872-901
    @Test
    fun `validates version information for version 7 and above`() {
        val border = 4
        val version = 7
        val size = 17 + 4 * version
        val payload = "VERSION INFO MUST BE CHECKED"
        val wrong = QRInfo.versionBits(version + 1)
        fun make() = TestEncoder.encodeRaw(payload, version = version, border = border, mask = 0, ecc = L)
        fun write(raw: Array<BooleanArray>, copy: Int, i: Int, value: Boolean? = null) {
            val x = size - 11 + (i % 3)
            val y = i / 3
            val row = border + (if (copy != 0) x else y)
            val col = border + (if (copy != 0) y else x)
            raw[row][col] = value ?: !raw[row][col]
        }
        // Either physical copy can establish the dimension, including at BCH radius 3.
        for (good in 0..1) {
            val raw = make()
            for (i in 0 until 18) write(raw, if (good != 0) 0 else 1, i, ((wrong shr i) and 1) != 0)
            for (i in listOf(0, 7, 14)) write(raw, good, i)
            assertEquals(payload, read(TestImages.matrixToImage(raw, 4)), "good copy $good")
        }
        // Exact words for another version cannot override the sampled dimension.
        val raw = make()
        for (i in 0 until 18) {
            val value = ((wrong shr i) and 1) != 0
            write(raw, 0, i, value)
            write(raw, 1, i, value)
        }
        assertThrows<QRDecodingException> { read(TestImages.matrixToImage(raw, 4)) }
    }

    // L71-80
    @Test
    fun `grayscale raster decodes like its RGBA rendering`() {
        val raw = TestEncoder.encodeRaw("HELLO WORLD", version = 1, border = 2)
        val img = TestImages.matrixToImage(raw, 4)
        assertEquals("HELLO WORLD", read(img))
        assertEquals("HELLO WORLD", read(gray(img)))
    }

    @Test
    fun `inverted raster decodes`() {
        val text = "INVERTED RASTER"
        val img = TestImages.matrixToImage(TestEncoder.encodeRaw(text, border = 4), 4)
        val negated = Image(img.width, img.height, ByteArray(img.data.size) { i ->
            if (i % 4 == 3) img.data[i] else (255 - (img.data[i].toInt() and 0xFF)).toByte()
        })
        assertEquals(text, read(negated))
    }

    // L1898-1912 (the photo half uses the bit-exact luma reader instead of jpeg-js RGBA)
    @Test
    fun `mirrored raster and photo decode`() {
        val text = "MIRROR IMAGE 2026"
        val raster = TestImages.matrixToImage(TestEncoder.encodeRaw(text, border = 4), 4)
        assertEquals(text, read(TestImages.mirror(raster)))
        val photo = TestImages.readLuma("boofcv-v3/detection/blurred/image007.jpg")
        assertEquals(read(photo), read(TestImages.mirror(photo)))
    }
}
