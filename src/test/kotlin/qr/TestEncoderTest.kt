package qr

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestEncoderTest {
    private val eccs = ErrorCorrection.entries

    private fun decode(m: Array<BooleanArray>): String =
        QRDecoder.decode(TestImages.matrixToImage(m, scale = 4, rgb = false))

    @Test
    fun `roundtrip through the decoder for v1-10 x all ECC x all masks`() {
        val texts = listOf("HELLO WORLD" to TestEncoder.Encoding.ALPHANUMERIC, "hello, wörld" to TestEncoder.Encoding.BYTE)
        var checked = 0
        val failures = mutableListOf<String>()
        for ((text, enc) in texts) for (v in 1..10) for (ecc in eccs) {
            // Payloads that exceed a version's capacity (v1-H, v1-Q) are skipped, as in decode.test.ts L966-970.
            val fits = runCatching { TestEncoder.encodeCodewords(text, v, ecc, enc) }.isSuccess
            if (!fits) continue
            for (mask in 0..7) {
                val m = TestEncoder.encodeRaw(text, version = v, ecc = ecc, mask = mask, border = 4, encoding = enc)
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
            val m = TestEncoder.encodeRaw("HELLO WORLD +-*/: \$%.123456789", ecc = ecc, border = 4)
            assertEquals("HELLO WORLD +-*/: \$%.123456789", decode(m), "$ecc")
        }
        assertEquals("12345", decode(TestEncoder.encodeRaw("12345", border = 4)))
    }

    @Test
    fun `high versions have correct size, finders and timing`() {
        for (v in 11..40) for (border in listOf(1, 4)) {
            val m = TestEncoder.encodeRaw("A", version = v, ecc = ErrorCorrection.LOW, mask = v % 8, border = border)
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
    fun `encodeCodewords matches the v1-M HELLO WORLD vector`() {
        assertArrayEquals(
            helloData + helloEcc,
            TestEncoder.encodeCodewords("HELLO WORLD", 1, ErrorCorrection.MEDIUM, TestEncoder.Encoding.ALPHANUMERIC)
        )
    }

    @Test
    fun `symbolMatrix equals encodeRaw for the same codewords`() {
        val a = TestEncoder.symbolMatrix(1, ErrorCorrection.MEDIUM, helloData + helloEcc, mask = 0, border = 4)
        val b = TestEncoder.encodeRaw("HELLO WORLD", 1, ErrorCorrection.MEDIUM, mask = 0, border = 4)
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
            val actual = TestEncoder.encodeRaw(
                text,
                version = opts["version"]?.toInt(),
                ecc = opts["ecc"]?.let { ErrorCorrection.valueOf(it.uppercase()) } ?: ErrorCorrection.MEDIUM,
                mask = opts["mask"]?.toInt(),
                border = opts["border"]?.toInt() ?: 2,
                encoding = opts["encoding"]?.let { TestEncoder.Encoding.valueOf(it.uppercase()) },
            )
            assertTrue(expected.contentDeepEquals(actual), "fixture mismatch: $header")
            cases++
        }
        assertEquals(6, cases)
    }

    @Test
    fun `sizeEncode returns modules per side`() {
        assertEquals(21, TestEncoder.sizeEncode(1))
        assertEquals(25, TestEncoder.sizeEncode(2))
        assertEquals(177, TestEncoder.sizeEncode(40))
    }

    @Test
    fun `alignmentPatterns matches the spec`() {
        assertEquals(0, TestEncoder.alignmentPatterns(1).size)
        assertArrayEquals(intArrayOf(6, 18), TestEncoder.alignmentPatterns(2))
        val v7 = TestEncoder.alignmentPatterns(7)
        assertEquals(3, v7.size)
        assertEquals(6, v7[0])
    }
}
