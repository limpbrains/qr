package qr

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Option validation and version selection, ported from qr.test.ts L553-801. JS-only cases that the
 * Kotlin types make impossible (non-string text, array opts, fractional/NaN/Infinity numbers,
 * non-Uint8Array encoder output, cross-realm arrays) are not ported.
 */
class EncodeOptionsTest {
    private fun raw(text: String, o: EncodeOptions = EncodeOptions()) = QREncoder.encodeRaw(text, o)

    private fun message(block: () -> Unit): String? = assertThrows<IllegalArgumentException> { block() }.message

    private fun counting(): Pair<(String) -> ByteArray, () -> Int> {
        var calls = 0
        // qr.test.ts L775-779: Uint8Array.from(s, c => c.charCodeAt(0))
        val enc: (String) -> ByteArray = { s ->
            calls++
            ByteArray(s.length) { s[it].code.toByte() }
        }
        return enc to { calls }
    }

    @Test
    fun `version is validated before the textEncoder runs`() {
        // qr.test.ts L560-587, L589-622 (version range)
        for (v in listOf(0, 41)) {
            val (enc, calls) = counting()
            assertEquals(
                "Invalid version=$v. Expected number [1..40]",
                message { raw("x", EncodeOptions(encoding = Encoding.BYTE, version = v, textEncoder = enc)) },
            )
            assertEquals(0, calls())
        }
    }

    @Test
    fun `border, mask and scale validation messages`() {
        // qr.test.ts L625-627
        assertEquals("invalid border=0", message { raw("x", EncodeOptions(border = 0)) })
        assertEquals("invalid border=-1", message { raw("x", EncodeOptions(border = -1)) })
        // index.ts L1196-1197, L1235-1241
        assertEquals("invalid mask=8", message { raw("x", EncodeOptions(mask = 8)) })
        assertEquals("invalid mask=-1", message { raw("x", EncodeOptions(mask = -1)) })
        assertEquals("invalid scale factor: 0", message { raw("x", EncodeOptions(scale = 0)) })
        assertEquals("invalid scale factor: 1025", message { raw("x", EncodeOptions(scale = 1025)) })
    }

    @Test
    fun `border 1 equals border 2 with the padding sliced`() {
        // qr.test.ts L628-637
        val padded = raw("x", EncodeOptions(border = 1))
        val sliced = padded.slice(1 until padded.size - 1).map { it.copyOfRange(1, it.size - 1) }
        assertEquals(21, sliced.size)
        val b2 = raw("x", EncodeOptions(border = 2))
        val sliced2 = b2.slice(2 until b2.size - 2).map { it.copyOfRange(2, it.size - 2) }
        assertTrue(sliced.toTypedArray().contentDeepEquals(sliced2.toTypedArray()))
    }

    @Test
    fun `output-specific dimension limits`() {
        // qr.test.ts L653-670
        val expanded = EncodeOptions(version = 1, border = 502)
        val m1024 = "invalid opts: output is 1025x1025 (max 1024), reduce border/scale"
        assertEquals(m1024, message { QREncoder.encodeRaw("x", expanded) })
        assertEquals(m1024, message { QREncoder.encodeTerm("x", expanded) })
        assertEquals(m1024, message { QREncoder.encodeSvg("x", expanded) })
        assertTrue(QREncoder.encodeGif("x", expanded).isNotEmpty())
        val compact = EncodeOptions(version = 1, border = 2038)
        val m4096 = "invalid opts: output is 4097x4097 (max 4096), reduce border/scale"
        assertEquals(m4096, message { QREncoder.encodeAscii("x", compact) })
        assertEquals(m4096, message { QREncoder.encodeGif("x", compact) })
        assertEquals(m4096, message { QREncoder.encodeDataUrl("x", compact) })
    }

    @Test
    fun `explicit mode data is validated before capacity`() {
        // qr.test.ts L672-681
        assertEquals(
            "Unknown letter: \"A\". Allowed: 0123456789",
            message { raw("A".repeat(100), EncodeOptions(version = 1, encoding = Encoding.NUMERIC)) },
        )
        assertEquals(
            "Unknown letter: \"a\". Allowed: 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:",
            message { raw("a".repeat(100), EncodeOptions(version = 1, encoding = Encoding.ALPHANUMERIC)) },
        )
        // index.ts L1190: the message shows the whole code point (String.fromCodePoint), not a lone surrogate.
        assertEquals(
            "Unknown letter: \"🔻\". Allowed: 0123456789",
            message { raw("1🔻", EncodeOptions(encoding = Encoding.NUMERIC)) },
        )
    }

    @Test
    fun `auto version picks the smallest fitting version`() {
        // qr.test.ts L683-712
        val gen = linkedMapOf<Encoding, (Int) -> String>(
            Encoding.NUMERIC to { n -> "1".repeat(n) },
            Encoding.ALPHANUMERIC to { n -> "A".repeat(n) },
            Encoding.BYTE to { n -> "a".repeat(n) },
        )
        fun versionOf(r: Array<BooleanArray>) = (r.size - 4 - 17) / 4
        for (ecc in ErrorCorrection.entries) for ((type, g) in gen) for (n in intArrayOf(0, 1, 17, 42, 100, 255, 256, 500)) {
            val text = g(n)
            var expVer: Int? = null
            for (i in 1..40) {
                val ok = runCatching { Segments.encodeData(i, ecc, text, type, if (type == Encoding.BYTE) Utf8.encode(text) else null) }.isSuccess
                if (ok) {
                    expVer = i
                    break
                }
            }
            assertEquals(expVer, versionOf(raw(text, EncodeOptions(ecc = ecc, encoding = type))), "ecc=$ecc type=$type n=$n")
        }
        assertEquals(1, versionOf(raw("1".repeat(41), EncodeOptions(ecc = ErrorCorrection.LOW))))
        assertEquals(2, versionOf(raw("1".repeat(42), EncodeOptions(ecc = ErrorCorrection.LOW))))
    }

    @Test
    fun `capacity overflow throws`() {
        // qr.test.ts L714-723
        assertEquals("Capacity overflow", message { raw("1".repeat(8000)) })
        assertEquals("Capacity overflow", message { raw("a".repeat(3000), EncodeOptions(ecc = ErrorCorrection.LOW)) })
        assertEquals("Capacity overflow", message { raw("HELLO WORLD", EncodeOptions(version = 1, ecc = ErrorCorrection.HIGH)) })
        // index.ts L1233: capacity is checked before border.
        assertEquals("Capacity overflow", message { raw("1".repeat(8000), EncodeOptions(border = 0)) })
    }

    @Test
    fun `built-in UTF-8 capacity is checked up front, a custom encoder runs once`() {
        // qr.test.ts L725-762 (Kotlin's built-in UTF-8 encoder cannot be swapped to count its calls)
        val v1l = EncodeOptions(version = 1, ecc = ErrorCorrection.LOW, encoding = Encoding.BYTE)
        assertEquals("Capacity overflow", message { raw("a".repeat(18), v1l) })
        assertEquals("Capacity overflow", message { raw("é".repeat(9), v1l) })
        var customCalls = 0
        raw("a".repeat(18), v1l.copy(textEncoder = { customCalls++; byteArrayOf(97) }))
        assertEquals(1, customCalls)
        // A custom encoder's output is the source of truth: it still runs once and the overflow still throws.
        val (enc, calls) = counting()
        assertEquals("Capacity overflow", message { raw("a".repeat(18), v1l.copy(textEncoder = enc)) })
        assertEquals(1, calls())
    }

    @Test
    fun `empty string encodes as v1 numeric`() {
        // qr.test.ts L764-768
        assertEquals(21 + 4, raw("").size)
        assertEquals(Encoding.NUMERIC, Segments.detectType(""))
    }

    @Test
    fun `custom textEncoder runs exactly once`() {
        // qr.test.ts L770-792
        val (auto, autoCalls) = counting()
        raw("abc", EncodeOptions(encoding = Encoding.BYTE, textEncoder = auto))
        assertEquals(1, autoCalls())
        val (explicit, explicitCalls) = counting()
        raw("abc", EncodeOptions(encoding = Encoding.BYTE, version = 2, textEncoder = explicit))
        assertEquals(1, explicitCalls())
        assertTrue(
            raw("abc", EncodeOptions(encoding = Encoding.BYTE, textEncoder = counting().first))
                .contentDeepEquals(raw("abc", EncodeOptions(encoding = Encoding.BYTE)))
        )
    }

    @Test
    fun `explicit mask 0 is honored`() {
        // qr.test.ts L794-801
        val data = Segments.encodeData(1, ErrorCorrection.LOW, "X", Encoding.ALPHANUMERIC, null)
        val expected = Symbol.withBorder(Symbol.draw(1, ErrorCorrection.LOW, data, 0), 1)
        val auto = raw("X", EncodeOptions(ecc = ErrorCorrection.LOW, mask = 0, border = 1))
        assertEquals(23, auto.size)
        assertTrue(expected.contentDeepEquals(auto))
    }

    @Test
    fun `lone surrogate encodes as U+FFFD and matches the JS raw fixture`() {
        assertArrayEquals(
            byteArrayOf(0x61, 0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte(), 0x62),
            Utf8.encode("a\uD800b"),
        )
        // encoder-renders.txt case 10: JS encodeQR('a\ud800b', 'raw')
        val line = javaClass.getResourceAsStream("/encoder-renders.txt")!!.bufferedReader(Charsets.UTF_8).readLines()
            .first { it.startsWith("{\"text\":\"a\\ud800b\"") }
        @Suppress("UNCHECKED_CAST")
        val expected = (MiniJson.parse(line) as Map<String, Any?>)["value"] as List<String>
        assertEquals(expected, raw("a\uD800b").map { r -> r.joinToString("") { if (it) "1" else "0" } })
    }
}
