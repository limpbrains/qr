package qr

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Utf8Test {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

    @Test
    fun `encode and length agree with the JVM on well-formed text`() {
        for (s in listOf("", "hello", "é", "ü€", "中文", "😀", "aé中😀z")) {
            val expected = s.toByteArray(Charsets.UTF_8)
            assertArrayEquals(expected, Utf8.encode(s), s)
            assertEquals(expected.size, Utf8.length(s), s)
        }
        assertEquals(2, Utf8.length("é"))
        assertEquals(3, Utf8.length("€"))
        assertEquals(4, Utf8.length("😀"))
    }

    @Test
    fun `lone surrogates encode as U+FFFD like WHATWG TextEncoder`() {
        assertEquals("61 EF BF BD 62", hex(Utf8.encode("a\uD800b")))
        assertEquals(5, Utf8.length("a\uD800b"))
        assertEquals("EF BF BD", hex(Utf8.encode("\uDC00")))
        assertEquals("EF BF BD", hex(Utf8.encode("\uD800")))
        assertEquals("EF BF BD EF BF BD", hex(Utf8.encode("\uDC00\uD800")))
        assertEquals(6, Utf8.length("\uDC00\uD800"))
    }
}
