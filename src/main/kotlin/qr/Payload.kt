package qr

import java.nio.charset.Charset

/**
 * Segment parser for corrected data codewords (decode.ts L206-369, `Payload.create/decode/finish`).
 * Instances reuse their text and byte buffers and are not thread-safe.
 *
 * @param textDecoder optional custom byte-segment decoder `(bytes, eci) -> text`. When set, every
 *   byte segment goes to it (any ECI accepted), mirroring JS `opts.textDecoder`.
 */
internal class Payload(private val textDecoder: ((ByteArray, Int) -> String)? = null) {
    private var data = IntArray(0)
    private var dataLen = 0
    private var position = 0
    private var bytes = ByteArray(0)
    private val res = StringBuilder()

    // decode.ts L261-272: bit reader; -1 when fewer than `bits` bits remain.
    private fun read(bits: Int): Int {
        val start = position
        if (start + bits > dataLen * 8) return -1
        val byte = start shr 3
        // No field exceeds 16 bits, so three bytes cover it; bytes past the array read as zero.
        val window = (at(byte) shl 16) or (at(byte + 1) shl 8) or at(byte + 2)
        position = start + bits
        return (window shr (24 - (start and 7) - bits)) and ((1 shl bits) - 1)
    }

    private fun at(i: Int): Int = if (i < data.size) data[i] else 0

    /** decode.ts L1576-1577 (`_QRScanner.clean`): reset the cursor and wipe the byte scratch. */
    fun clean() {
        position = 0
        bytes.fill(0)
    }

    /**
     * Parse [dataLen] data codewords (values 0..255) of a symbol of [version].
     * Returns decoded text, or null (=> [Fail.DATA]) exactly where JS returns `FAIL.data`.
     */
    fun decode(data: IntArray, dataLen: Int, version: Int): String? {
        // decode.ts L275-359
        this.data = data
        this.dataLen = dataLen
        position = 0
        val cls = if (version < 10) 0 else if (version < 27) 1 else 2
        var eci = 26
        res.setLength(0)
        while (position + 4 <= dataLen * 8) {
            val mode = read(4)
            if (mode == 0) break
            if (mode == 7) {
                val b0 = read(8)
                if (b0 < 0) return null
                if ((b0 and 0x80) == 0) eci = b0
                else {
                    val len = if ((b0 and 0xc0) == 0x80) 8 else 16
                    val value = read(len)
                    if (value < 0) return null
                    eci = (b0 and (if (len == 8) 0x3f else 0x1f)) shl len
                    eci = eci or value
                }
                continue
            }
            when (mode) {
                1 -> {
                    var length = read(NUMERIC_LENGTH_BITS[cls])
                    if (length < 0) return null
                    while (length >= 3) {
                        val value = read(10)
                        if (value < 0 || value >= 1000) return null
                        appendDigits(value, 3)
                        length -= 3
                    }
                    if (length != 0) {
                        val value = read(if (length == 2) 7 else 4)
                        if (value < 0 || value >= (if (length == 2) 100 else 10)) return null
                        appendDigits(value, length)
                    }
                }
                2 -> {
                    var length = read(ALPHANUMERIC_LENGTH_BITS[cls])
                    if (length < 0) return null
                    while (length >= 2) {
                        val value = read(11)
                        if (value < 0 || value >= 45 * 45) return null
                        res.append(QRInfo.ALPHANUMERIC[value / 45]).append(QRInfo.ALPHANUMERIC[value % 45])
                        length -= 2
                    }
                    if (length != 0) {
                        val value = read(6)
                        if (value < 0 || value >= 45) return null
                        res.append(QRInfo.ALPHANUMERIC[value])
                    }
                }
                4 -> {
                    val length = read(BYTE_LENGTH_BITS[cls])
                    if (length < 0 || position + 8 * length > dataLen * 8) return null
                    val custom = textDecoder
                    if (custom != null) {
                        // JS defers these parts to Payload.finish (L361-368); RS already passed here.
                        val segment = ByteArray(length)
                        for (i in 0 until length) segment[i] = read(8).toByte()
                        res.append(custom(segment, eci))
                    } else {
                        val charset = charset(eci) ?: return null
                        if (bytes.size < dataLen) bytes = ByteArray(dataLen)
                        for (i in 0 until length) bytes[i] = read(8).toByte()
                        // String(bytes, charset) substitutes U+FFFD for malformed input, like TextDecoder.
                        val text = String(bytes, 0, length, charset)
                        // WHATWG TextDecoder strips a leading BOM for UTF-16BE/UTF-8; Java does not.
                        val skip = if ((eci == 25 || eci == 26) && text.isNotEmpty() && text[0] == '\uFEFF') 1 else 0
                        res.append(text, skip, text.length)
                    }
                }
                else -> return null
            }
        }
        return res.toString()
    }

    private fun appendDigits(value: Int, count: Int) {
        if (count >= 3) res.append('0' + value / 100)
        if (count >= 2) res.append('0' + (value / 10) % 10)
        res.append('0' + value % 10)
    }

    companion object {
        private val NUMERIC_LENGTH_BITS = intArrayOf(10, 12, 14)
        private val ALPHANUMERIC_LENGTH_BITS = intArrayOf(9, 11, 13)
        private val BYTE_LENGTH_BITS = intArrayOf(8, 16, 16)

        /**
         * ECI assignment -> Java charset implementing the WHATWG decoder that the JS label
         * (decode.ts L206-233) resolves to.
         */
        private val ECI_CHARSETS: Map<Int, String> = mapOf(
            1 to "windows-1252",
            2 to "IBM437",
            3 to "windows-1252",
            4 to "ISO-8859-2",
            5 to "ISO-8859-3",
            6 to "ISO-8859-4",
            7 to "ISO-8859-5",
            8 to "ISO-8859-6",
            9 to "ISO-8859-7",
            10 to "ISO-8859-8",
            11 to "windows-1254",
            13 to "x-windows-874",
            15 to "ISO-8859-13",
            16 to "ISO-8859-14",
            17 to "ISO-8859-15",
            18 to "ISO-8859-16",
            20 to "windows-31j",
            21 to "windows-1250",
            22 to "windows-1251",
            23 to "windows-1252",
            24 to "windows-1256",
            25 to "UTF-16BE",
            26 to "UTF-8",
            28 to "Big5-HKSCS",
            29 to "GB18030",
            30 to "x-windows-949"
        )

        private val resolved = java.util.concurrent.ConcurrentHashMap<Int, Charset>()
        private val unsupported = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

        /** Lazily resolved charset for [eci]; null when unknown or unsupported on this JVM. */
        private fun charset(eci: Int): Charset? {
            resolved[eci]?.let { return it }
            if (eci in unsupported) return null
            val name = ECI_CHARSETS[eci] ?: return null
            val cs = try {
                if (Charset.isSupported(name)) Charset.forName(name) else null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (cs == null) unsupported.add(eci) else resolved[eci] = cs
            return cs
        }
    }
}
