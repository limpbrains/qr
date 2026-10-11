package qr

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EncoderTest {
    private val eccs = ErrorCorrection.entries

    private fun decode(m: Array<BooleanArray>): String =
        QRDecoder.decode(TestImages.matrixToImage(m, scale = 4, rgb = false))

    @Test
    fun `roundtrip through the decoder for v1-10 x all ECC x all masks`() {
        val texts = listOf("HELLO WORLD" to Encoding.ALPHANUMERIC, "hello, wörld" to Encoding.BYTE)
        var checked = 0
        val failures = mutableListOf<String>()
        for ((text, enc) in texts) for (v in 1..10) for (ecc in eccs) {
            // Payloads that exceed a version's capacity (v1-H, v1-Q) are skipped, as in decode.test.ts L966-970.
            val fits = runCatching { Segments.encodeData(v, ecc, text, enc, if (enc == Encoding.BYTE) Utf8.encode(text) else null) }.isSuccess
            if (!fits) continue
            for (mask in 0..7) {
                val m = QREncoder.encodeRaw(text, EncodeOptions(version = v, ecc = ecc, mask = mask, border = 4, encoding = enc))
                if (runCatching { decode(m) }.getOrNull() != text) failures.add("$text v$v $ecc mask$mask")
                checked++
            }
        }
        assertEquals((2 * 10 * 4 - 3) * 8, checked) // skipped: v1-H for both payloads, v1-Q for the byte one
        // The old decoder missed "HELLO WORLD v10 QUARTILE mask6"; the v0.7.2 port reads every one.
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `auto version and auto mask roundtrip`() {
        for (ecc in eccs) {
            val m = QREncoder.encodeRaw("HELLO WORLD +-*/: \$%.123456789", EncodeOptions(ecc = ecc, border = 4))
            assertEquals("HELLO WORLD +-*/: \$%.123456789", decode(m), "$ecc")
        }
        assertEquals("12345", decode(QREncoder.encodeRaw("12345", EncodeOptions(border = 4))))
    }

    @Test
    fun `high versions have correct size, finders and timing`() {
        for (v in 11..40) for (border in listOf(1, 4)) {
            val m = QREncoder.encodeRaw("A", EncodeOptions(version = v, ecc = ErrorCorrection.LOW, mask = v % 8, border = border))
            val size = 17 + 4 * v
            assertEquals(size + 2 * border, m.size)
            assertTrue(m.all { it.size == m.size })
            fun at(x: Int, y: Int) = m[border + y][border + x]
            for ((fx, fy) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
                for (dy in -1..7) for (dx in -1..7) {
                    val x = fx + dx
                    val y = fy + dy
                    if (x < 0 || y < 0 || x >= size || y >= size) continue
                    val on = dx in 0..6 && dy in 0..6 &&
                        (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                    assertEquals(on, at(x, y), "v$v finder ($x,$y)")
                }
            }
            for (i in 8 until size - 8) {
                assertEquals(i % 2 == 0, at(i, 6), "v$v timing row $i")
                assertEquals(i % 2 == 0, at(6, i), "v$v timing col $i")
            }
            assertTrue(at(8, size - 8), "dark module")
            for (i in 0 until border) assertTrue(m[i].none { it } && m[m.size - 1 - i].none { it })
        }
    }

    // ISO/IEC 18004 / thonky v1-M "HELLO WORLD"; the ECC words were confirmed against
    // JS `_tests.encodeData(1, 'medium', 'HELLO WORLD', 'alphanumeric')`.
    private val helloData = intArrayOf(0x20, 0x5b, 0x0b, 0x78, 0xd1, 0x72, 0xdc, 0x4d, 0x43, 0x40, 0xec, 0x11, 0xec, 0x11, 0xec, 0x11)
    private val helloEcc = intArrayOf(0xc4, 0x23, 0x27, 0x77, 0xeb, 0xd7, 0xe7, 0xe2, 0x5d, 0x17)

    @Test
    fun `encodeData matches the v1-M HELLO WORLD vector`() {
        assertArrayEquals(
            helloData + helloEcc,
            Segments.encodeData(1, ErrorCorrection.MEDIUM, "HELLO WORLD", Encoding.ALPHANUMERIC, null)
        )
    }

    @Test
    fun `symbolMatrix equals encodeRaw for the same codewords`() {
        val a = TestSymbols.symbolMatrix(1, ErrorCorrection.MEDIUM, helloData + helloEcc, mask = 0, border = 4)
        val b = QREncoder.encodeRaw("HELLO WORLD", EncodeOptions(version = 1, ecc = ErrorCorrection.MEDIUM, mask = 0, border = 4))
        assertTrue(a.contentDeepEquals(b))
        assertEquals("HELLO WORLD", decode(a))
    }

    @Test
    fun `matrices are identical to JS encodeQR raw output`() {
        val lines = javaClass.getResourceAsStream("/encoder-fixtures.txt")!!.bufferedReader(Charsets.UTF_8).readLines()
            .filter { !it.startsWith("#") }
        var i = 0
        var cases = 0
        while (i < lines.size) {
            val header = lines[i++]
            require(header.startsWith("case ")) { "bad fixture line: $header" }
            val (textJson, optsJson) = header.removePrefix("case ").split("\t")
            val text = textJson.removeSurrounding("\"")
            val opts = Regex("\"(\\w+)\":\"?([^,\"}]*)").findAll(optsJson).associate { it.groupValues[1] to it.groupValues[2] }
            val rows = ArrayList<String>()
            while (i < lines.size && !lines[i].startsWith("case ")) rows.add(lines[i++])
            val expected = rows.map { r -> BooleanArray(r.length) { r[it] == '1' } }.toTypedArray()
            val actual = QREncoder.encodeRaw(
                text,
                EncodeOptions(
                    version = opts["version"]?.toInt(),
                    ecc = opts["ecc"]?.let { ErrorCorrection.valueOf(it.uppercase()) } ?: ErrorCorrection.MEDIUM,
                    mask = opts["mask"]?.toInt(),
                    border = opts["border"]?.toInt() ?: 2,
                    encoding = opts["encoding"]?.let { Encoding.valueOf(it.uppercase()) },
                ),
            )
            assertTrue(expected.contentDeepEquals(actual), "fixture mismatch: $header")
            cases++
        }
        assertEquals(6, cases)
    }

    @Test
    fun `sizeEncode returns modules per side`() {
        assertEquals(21, Symbol.size(1))
        assertEquals(25, Symbol.size(2))
        assertEquals(177, Symbol.size(40))
    }

    // ---- qr.test.ts drawSymbol goldens (L75-388): drawQR(ver, 'low', data, mask).border(2) -> bmToASCII ----

    private fun asciiOf(m: Array<BooleanArray>): String = Renderers.ascii(Raster(m, m.size, IntArray(m.size) { it }))

    private fun drawAscii(ver: Int, data: String, mask: Int): String =
        asciiOf(Symbol.withBorder(Symbol.draw(ver, ErrorCorrection.LOW, JsEncoderGoldens.ints(data), mask), 2))

    @Test
    fun `qr v1 golden`() = assertEquals(JsEncoderGoldens.V1_MASK0, drawAscii(1, JsEncoderGoldens.V1_DATA, 0))

    @Test
    fun `qr v5 golden`() = assertEquals(JsEncoderGoldens.V5_MASK0, drawAscii(5, JsEncoderGoldens.V5_DATA, 0))

    @Test
    fun `qr v10 goldens, masks 0 6 1`() {
        assertEquals(JsEncoderGoldens.V10_MASK0, drawAscii(10, JsEncoderGoldens.V10_DATA, 0))
        assertEquals(JsEncoderGoldens.V10_MASK6, drawAscii(10, JsEncoderGoldens.V10_DATA, 6))
        assertEquals(JsEncoderGoldens.V10_MASK1, drawAscii(10, JsEncoderGoldens.V10_DATA, 1))
    }

    @Test
    fun `qr v20 golden`() = assertEquals(JsEncoderGoldens.V20_MASK0, drawAscii(20, JsEncoderGoldens.V20_DATA, 0))

    // ---- qr.test.ts Full API (L827-849, L871-896) ----

    @Test
    fun `full API ascii golden, emoji`() =
        assertEquals(JsEncoderGoldens.FULL_API_EMOJI, QREncoder.encodeAscii("#\uFE0F\u20E3\uD83E\uDDDC\u200D\u2642\uFE0F\uD83C\uDFCE\uD83D\uDD0D\uD83D\uDD3B"))

    @Test
    fun `full API ascii golden, url`() =
        assertEquals(JsEncoderGoldens.FULL_API_URL, QREncoder.encodeAscii("https://www.youtube.com/watch?v=eBGIQ7ZuuiU"))

    // ---- encode.test.ts ----

    @Test
    fun `BYTES matches ISO Table 1 literals`() {
        // encode.test.ts L20-26
        assertArrayEquals(
            JsEncoderGoldens.ints(
                "26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655, 733, 815, 901, 991, 1085," +
                    "1156, 1258, 1364, 1474, 1588, 1706, 1828, 1921, 2051, 2185, 2323, 2465, 2611, 2761, 2876, 3034, 3196, 3362, 3532, 3706,"
            ),
            QRInfo.BYTES,
        )
    }

    @Test
    fun `Table 9 packed strings match WORDS_PER_BLOCK and ECC_BLOCKS`() {
        // encode.test.ts L38-60: one value per char, charCode - 40, 40 versions per level (low/medium/quartile/high).
        fun unpack(packed: String): List<List<Int>> = (0 until 4).map { i -> packed.substring(40 * i, 40 * (i + 1)).map { it.code - 40 } }
        assertEquals(
            unpack("/27<B:<@F:<@BF>@DFDDDDFFBDFFFFFFFFFFFFFF28B:@8:>>BF>>@@DDBBBBDDDDDDDDDDDDDDDDDDD5>:B:@:><@DB@<F@DDBFDFFFFDFFFFFFFFFFFFFF9D>8>DBB@D@D>@@FDDBDF@FFFFFFFFFFFFFFFFFF"),
            QRInfo.WORDS_PER_BLOCK.map { it.toList() },
        )
        assertEquals(
            unpack(")))))****,,,,,..../0011244456789:;;<=>@A)))**,,,---01122356899:<=?ABDEGIKMNPSUWY))**,,..000248498:=<??ACEJJKNPSUX[]`cfil))*,,,-.003388:8;=AAAJFHKMPRUX[^adgjnruy"),
            QRInfo.ECC_BLOCKS.map { it.toList() },
        )
    }

    @Test
    fun `detectType vectors`() {
        // encode.test.ts L62-71
        assertEquals(Encoding.NUMERIC, Segments.detectType("12345678"))
        assertEquals(Encoding.ALPHANUMERIC, Segments.detectType("HELLO WORLD"))
        assertEquals(Encoding.BYTE, Segments.detectType("#\uFE0F\u20E3\uD83E\uDDDC\u200D\u2642\uFE0F\uD83C\uDFCE\uD83D\uDD0D\uD83D\uDD3B"))
    }

    @Test
    fun `encodeData golden codewords`() {
        // encode.test.ts L73-393: _tests.encode(version, ecc, text, detectType(text))
        assertEquals(12, JsEncoderGoldens.ENCODE_DATA.size)
        for (v in JsEncoderGoldens.ENCODE_DATA) {
            val type = Segments.detectType(v.text)
            val utf8 = if (type == Encoding.BYTE) Utf8.encode(v.text) else null
            assertArrayEquals(JsEncoderGoldens.ints(v.exp), Segments.encodeData(v.version, v.ecc, v.text, type, utf8), "${v.text} v${v.version} ${v.ecc}")
        }
    }

    @Test
    fun `alignmentPatterns matches the qrbtf table`() {
        // qr.test.ts L538-551 (the RangeError/TypeError cases are type- or range-impossible internals here)
        assertArrayEquals(intArrayOf(), Symbol.alignmentPatterns(1))
        assertArrayEquals(intArrayOf(6, 18), Symbol.alignmentPatterns(2))
        assertArrayEquals(intArrayOf(6, 22, 38), Symbol.alignmentPatterns(7))
        assertArrayEquals(intArrayOf(6, 26, 46, 66), Symbol.alignmentPatterns(14))
        assertArrayEquals(intArrayOf(6, 30, 58, 86, 114, 142, 170), Symbol.alignmentPatterns(40))
    }
}
