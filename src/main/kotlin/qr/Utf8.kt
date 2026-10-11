package qr

/** WHATWG `TextEncoder` UTF-8: lone surrogates encode as U+FFFD (EF BF BD), never `String.toByteArray`. */
internal object Utf8 {
    /** Exact encoded byte count without allocating (index.ts utf8Length L880-896). */
    fun length(s: String): Int {
        var length = 0
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            if (c < 0x80) length++
            else if (c < 0x800) length += 2
            else if (c < 0xd800 || c > 0xdfff) length += 3
            else if (c <= 0xdbff && i + 1 < s.length) {
                val next = s[i + 1].code
                if (next in 0xdc00..0xdfff) {
                    length += 4
                    i++
                } else length += 3
            } else length += 3
            i++
        }
        return length
    }

    /** WHATWG UTF-8 encode over UTF-16 units: a valid pair → 4 bytes, a lone surrogate → EF BF BD. */
    fun encode(s: String): ByteArray {
        val out = ByteArray(length(s))
        var p = 0
        var i = 0
        while (i < s.length) {
            var c = s[i].code
            if (c in 0xd800..0xdfff) {
                if (c <= 0xdbff && i + 1 < s.length && s[i + 1].code in 0xdc00..0xdfff) {
                    c = 0x10000 + ((c - 0xd800) shl 10) + (s[i + 1].code - 0xdc00)
                    i++
                } else c = 0xfffd
            }
            when {
                c < 0x80 -> out[p++] = c.toByte()
                c < 0x800 -> {
                    out[p++] = (0xc0 or (c shr 6)).toByte()
                    out[p++] = (0x80 or (c and 0x3f)).toByte()
                }
                c < 0x10000 -> {
                    out[p++] = (0xe0 or (c shr 12)).toByte()
                    out[p++] = (0x80 or ((c shr 6) and 0x3f)).toByte()
                    out[p++] = (0x80 or (c and 0x3f)).toByte()
                }
                else -> {
                    out[p++] = (0xf0 or (c shr 18)).toByte()
                    out[p++] = (0x80 or ((c shr 12) and 0x3f)).toByte()
                    out[p++] = (0x80 or ((c shr 6) and 0x3f)).toByte()
                    out[p++] = (0x80 or (c and 0x3f)).toByte()
                }
            }
            i++
        }
        return out
    }
}
