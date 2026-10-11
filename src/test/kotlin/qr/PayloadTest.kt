package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PayloadTest {
    /** MSB-first bit writer producing data codewords padded with 0xEC/0x11 like the encoder. */
    private class Bits {
        private val bits = StringBuilder()
        fun put(value: Int, width: Int): Bits {
            for (i in width - 1 downTo 0) bits.append(if ((value shr i) and 1 == 1) '1' else '0')
            return this
        }
        fun bytes(values: ByteArray): Bits {
            for (b in values) put(b.toInt() and 0xff, 8)
            return this
        }
        fun codewords(dataLen: Int): IntArray {
            while (bits.length % 8 != 0) bits.append('0')
            val res = IntArray(dataLen)
            val n = bits.length / 8
            require(n <= dataLen)
            for (i in 0 until n) res[i] = bits.substring(8 * i, 8 * i + 8).toInt(2)
            for (i in n until dataLen) res[i] = if ((i - n) % 2 == 0) 0xEC else 0x11
            return res
        }
    }

    private fun decode(words: IntArray, version: Int = 1, dataLen: Int = words.size) =
        Payload().decode(words, dataLen, version)

    @Test
    fun `numeric segment`() {
        val w = Bits().put(1, 4).put(8, 10).put(12, 10).put(345, 10).put(67, 7).put(0, 4).codewords(19)
        assertEquals("01234567", decode(w))
    }

    @Test
    fun `alphanumeric segment`() {
        val text = "HELLO WORLD"
        val b = Bits().put(2, 4).put(text.length, 9)
        var i = 0
        while (i + 1 < text.length) {
            b.put(QRInfo.ALPHANUMERIC.indexOf(text[i]) * 45 + QRInfo.ALPHANUMERIC.indexOf(text[i + 1]), 11)
            i += 2
        }
        b.put(QRInfo.ALPHANUMERIC.indexOf(text.last()), 6).put(0, 4)
        assertEquals(text, decode(b.codewords(19)))
    }

    @Test
    fun `byte segment defaults to UTF-8`() {
        val bytes = "héllo".toByteArray(Charsets.UTF_8)
        val w = Bits().put(4, 4).put(bytes.size, 8).bytes(bytes).put(0, 4).codewords(19)
        assertEquals("héllo", decode(w))
    }

    @Test
    fun `standard v1-M data vector`() {
        val data = intArrayOf(0x40, 0xd2, 0x75, 0x47, 0x76, 0x17, 0x32, 0x06, 0x27, 0x26, 0x96, 0xc6, 0xc6, 0x96, 0x70, 0xec)
        assertEquals("'Twas brillig", decode(data))
    }

    @Test
    fun `mixed segments in version class 1 use wider length fields`() {
        val bytes = "ab".toByteArray()
        val w = Bits().put(1, 4).put(3, 12).put(987, 10)
            .put(4, 4).put(bytes.size, 16).bytes(bytes)
            .put(2, 4).put(1, 11).put(36, 6)
            .put(0, 4).codewords(40)
        assertEquals("987ab ", decode(w, version = 10))
    }

    @Test
    fun `one-byte ECI designator selects latin-1 for later byte segments`() {
        val latin1 = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())
        val w = Bits().put(7, 4).put(3, 8).put(4, 4).put(latin1.size, 8).bytes(latin1).put(0, 4).codewords(19)
        assertEquals("café", decode(w))
    }

    @Test
    fun `two-byte ECI designator`() {
        // 0b10xxxxxx prefix: eci = (b0 & 0x3f) << 8 | next byte = 25 (UTF-16BE); leading BOM is stripped.
        val utf16 = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x41, 0x00, 0xE9.toByte())
        val w = Bits().put(7, 4).put(0x80, 8).put(25, 8).put(4, 4).put(utf16.size, 8).bytes(utf16).put(0, 4).codewords(19)
        assertEquals("Aé", decode(w))
    }

    @Test
    fun `three-byte ECI designator`() {
        // 0b11xxxxxx prefix: eci = (b0 & 0x1f) << 16 | next 16 bits = 3
        val w = Bits().put(7, 4).put(0xC0, 8).put(3, 16).put(4, 4).put(1, 8).put(0xE9, 8).put(0, 4).codewords(19)
        assertEquals("é", decode(w))
    }

    @Test
    fun `UTF-8 BOM is stripped like TextDecoder`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41)
        val w = Bits().put(4, 4).put(bytes.size, 8).bytes(bytes).put(0, 4).codewords(19)
        assertEquals("A", decode(w))
    }

    @Test
    fun `malformed UTF-8 decodes to replacement character`() {
        val w = Bits().put(4, 4).put(2, 8).put(0x41, 8).put(0xFF, 8).put(0, 4).codewords(19)
        assertEquals("A�", decode(w))
    }

    @Test
    fun `unknown ECI fails without custom textDecoder and is passed to it otherwise`() {
        val w = Bits().put(7, 4).put(12, 8).put(4, 4).put(2, 8).put(0x41, 8).put(0x42, 8).put(0, 4).codewords(19)
        assertNull(decode(w))
        val calls = mutableListOf<Pair<List<Byte>, Int>>()
        val custom = Payload { bytes, eci -> calls += bytes.toList() to eci; "<${bytes.size}>" }
        val w2 = Bits().put(1, 4).put(1, 10).put(5, 4).put(7, 4).put(12, 8).put(4, 4).put(2, 8)
            .put(0x41, 8).put(0x42, 8).put(0, 4).codewords(19)
        assertEquals("5<2>", custom.decode(w2, 19, 1))
        assertEquals(listOf(listOf<Byte>(0x41, 0x42) to 12), calls)
    }

    @Test
    fun `kanji and unknown modes fail`() {
        assertNull(decode(Bits().put(8, 4).put(1, 8).put(0, 13).codewords(19)))
        assertNull(decode(Bits().put(3, 4).put(0, 20).codewords(19)))
    }

    @Test
    fun `terminator stops parsing before padding`() {
        // Padding codewords 0xEC 0x11 would parse as byte mode if the terminator were ignored.
        val w = Bits().put(1, 4).put(1, 10).put(7, 4).put(0, 4).codewords(19)
        assertEquals(0xEC, w[3])
        assertEquals("7", decode(w))
        // Fewer than 4 bits left: loop ends without a terminator.
        val full = Bits().put(1, 4).put(2, 10).put(99, 7).put(0, 3).codewords(3)
        assertEquals("99", decode(full))
        // Only the first dataLen codewords are read.
        assertEquals("7", Payload().decode(w + intArrayOf(0x40, 0x10), 19, 1))
    }

    @Test
    fun `truncated header fails`() {
        // ECI designator byte missing.
        assertNull(decode(Bits().put(7, 4).put(0, 4).codewords(1)))
        // Numeric group cut short by the end of data.
        assertNull(decode(Bits().put(1, 4).put(6, 10).put(123, 10).codewords(3)))
    }

    @Test
    fun `byte segment length past the data fails`() {
        // decode.test.ts L817-824: v1-low byte header declaring 255 bytes.
        assertNull(decode(UPSTREAM_V1L_FIXTURES[0], version = 1, dataLen = 19))
    }

    @Test
    fun `out-of-range numeric and alphanumeric groups fail`() {
        // decode.test.ts L826-870: numeric, alphanumeric, two-digit, one-digit, one-character.
        for (words in UPSTREAM_V1L_FIXTURES.drop(1)) assertNull(decode(words, version = 1, dataLen = 19))
    }

    @Test
    fun `instance is reusable across decodes`() {
        val p = Payload()
        val a = Bits().put(4, 4).put(3, 8).bytes("abc".toByteArray()).put(0, 4).codewords(19)
        val b = Bits().put(4, 4).put(1, 8).bytes("z".toByteArray()).put(0, 4).codewords(19)
        assertEquals("abc", p.decode(a, 19, 1))
        assertEquals("z", p.decode(b, 19, 1))
        assertNull(p.decode(UPSTREAM_V1L_FIXTURES[1], 19, 1))
        assertEquals("abc", p.decode(a, 19, 1))
    }

    companion object {
        /** Full RS-valid v1-low codeword fixtures from decode.test.ts L817-870 (26 words, 19 data). */
        val UPSTREAM_V1L_FIXTURES: List<IntArray> = listOf(
            intArrayOf(79, 240, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 24, 229, 147, 129, 112, 149, 181),
            intArrayOf(16, 15, 232, 0, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 250, 119, 88, 124, 205, 218, 88),
            intArrayOf(32, 23, 233, 0, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 64, 102, 161, 90, 11, 93, 68),
            intArrayOf(16, 11, 32, 0, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 199, 62, 84, 61, 211, 36, 77),
            intArrayOf(16, 6, 128, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 233, 187, 201, 36, 145, 187, 3),
            intArrayOf(32, 13, 160, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17, 227, 12, 130, 229, 237, 161, 73),
        )
    }
}
