package com.keklol

import kotlin.math.abs

/**
 * Decodes QR code data from a bitmap.
 */
object BitDecoder {

    private const val MAX_BITS_ERROR = 3

    private data class InfoBits(
        val version1: Int,
        val version2: Int,
        val format1: Int,
        val format2: Int
    )

    private data class ParsedInfo(
        val version: Int,
        val ecc: ErrorCorrection,
        val mask: Mask
    )

    /**
     * Read format and version information bits from the bitmap.
     */
    private fun readInfoBits(b: Bitmap): InfoBits {
        val size = b.height

        fun readBit(x: Int, y: Int, out: Int): Int =
            (out shl 1) or (if (b.get(x, y) == true) 1 else 0)

        // Version information (for version >= 7)
        var version1 = 0
        for (y in 5 downTo 0) {
            for (x in size - 9 downTo size - 11) {
                version1 = readBit(x, y, version1)
            }
        }

        var version2 = 0
        for (x in 5 downTo 0) {
            for (y in size - 9 downTo size - 11) {
                version2 = readBit(x, y, version2)
            }
        }

        // Format information
        var format1 = 0
        for (x in 0 until 6) format1 = readBit(x, 8, format1)
        format1 = readBit(7, 8, format1)
        format1 = readBit(8, 8, format1)
        format1 = readBit(8, 7, format1)
        for (y in 5 downTo 0) format1 = readBit(8, y, format1)

        var format2 = 0
        for (y in size - 1 downTo size - 7) format2 = readBit(8, y, format2)
        for (x in size - 8 until size) format2 = readBit(x, 8, format2)

        return InfoBits(version1, version2, format1, format2)
    }

    /**
     * Population count (number of 1 bits).
     */
    private fun popcnt(a: Int): Int {
        var n = a
        var cnt = 0
        while (n != 0) {
            if (n and 1 != 0) cnt++
            n = n shr 1
        }
        return cnt
    }

    /**
     * Parse version and format information from the bitmap.
     */
    private fun parseInfo(b: Bitmap): ParsedInfo {
        val size = b.height
        val (version1, version2, format1, format2) = readInfoBits(b)

        // Guess format
        var format: Pair<ErrorCorrection, Mask>? = null
        var bestFormatScore = Int.MAX_VALUE
        var bestFormat: Pair<ErrorCorrection, Mask>? = null

        for (ecc in ErrorCorrection.entries) {
            for (mask in 0 until 8) {
                val bits = QRInfo.formatBits(ecc, mask)
                val cur = Pair(ecc, mask)
                if (bits == format1 || bits == format2) {
                    format = cur
                    break
                }
                val score1 = popcnt(format1 xor bits)
                val score2 = popcnt(format2 xor bits)
                val minScore = minOf(score1, score2)
                if (minScore < bestFormatScore) {
                    bestFormatScore = minScore
                    bestFormat = cur
                }
            }
            if (format != null) break
        }

        if (format == null && bestFormatScore <= MAX_BITS_ERROR) {
            format = bestFormat
        }

        if (format == null) {
            throw InvalidFormatException("Invalid format pattern")
        }

        // Guess version based on bitmap size
        var version: Int? = QRInfo.sizeDecode(size)
        if (version!! < 7) {
            QRInfo.validateVersion(version)
        } else {
            version = null
            var bestVerScore = Int.MAX_VALUE
            var bestVer: Int? = null

            for (ver in 7..40) {
                val bits = QRInfo.versionBits(ver)
                if (bits == version1 || bits == version2) {
                    version = ver
                    break
                }
                val score1 = popcnt(version1 xor bits)
                val score2 = popcnt(version2 xor bits)
                val minScore = minOf(score1, score2)
                if (minScore < bestVerScore) {
                    bestVerScore = minScore
                    bestVer = ver
                }
            }

            if (version == null && bestVerScore <= MAX_BITS_ERROR) {
                version = bestVer
            }

            if (version == null) {
                throw InvalidVersionException("Invalid version pattern")
            }

            if (QRInfo.sizeEncode(version) != size) {
                throw InvalidVersionException("Invalid version size: expected ${QRInfo.sizeEncode(version)}, got $size")
            }
        }

        return ParsedInfo(version, format.first, format.second)
    }

    /**
     * Convert a byte to a binary string with specified padding.
     */
    private fun bin(value: Int, pad: Int): String =
        value.toString(2).padStart(pad, '0')

    /**
     * Decode the bitmap and extract the encoded text.
     */
    fun decodeBitmap(b: Bitmap): String {
        val size = b.height

        if (size < 21 || (size and 0b11) != 1 || size != b.width) {
            throw QRDecodingException("decode: invalid size=$size")
        }

        val (version, ecc, mask) = parseInfo(b)
        val tpl = QRInfo.drawTemplate(version, ecc, mask)
        val capacity = QRInfo.capacity(version, ecc)

        val bytes = ByteArray(capacity.total)
        var pos = 0
        var buf = 0
        var bitPos = 0

        QRInfo.zigzag(tpl, mask) { x, y, m ->
            bitPos++
            buf = buf shl 1
            buf = buf or if ((b.get(x, y) == true) != m) 1 else 0
            if (bitPos == 8) {
                bytes[pos++] = buf.toByte()
                bitPos = 0
                buf = 0
            }
        }

        if (pos != capacity.total) {
            throw QRDecodingException("decode: pos=$pos, total=${capacity.total}")
        }

        // De-interleave and error correct
        val interleave = Interleave(version, ecc)
        val decoded = interleave.decode(bytes)

        // Convert to bit string
        var bits = decoded.joinToString("") { bin(it.toInt() and 0xFF, 8) }

        // Parse segments
        val result = StringBuilder()

        fun readBits(n: Int): String {
            if (n > bits.length) throw QRDecodingException("Not enough bits")
            val value = bits.substring(0, n)
            bits = bits.substring(n)
            return value
        }

        fun toNum(s: String): Int = s.toInt(2)

        val modes = mapOf(
            "0000" to "terminator",
            "0001" to "numeric",
            "0010" to "alphanumeric",
            "0100" to "byte",
            "0111" to "eci",
            "1000" to "kanji"
        )

        while (true) {
            if (bits.length < 4) break
            val modeBits = readBits(4)
            val mode = modes[modeBits]
                ?: throw QRDecodingException("Unknown modeBits=$modeBits result=\"$result\"")

            if (mode == "terminator") break

            val type = when (mode) {
                "numeric" -> EncodingType.NUMERIC
                "alphanumeric" -> EncodingType.ALPHANUMERIC
                "byte" -> EncodingType.BYTE
                else -> throw QRDecodingException("Unsupported mode=$mode")
            }

            val countBits = QRInfo.lengthBits(version, type)
            var count = toNum(readBits(countBits))

            when (mode) {
                "numeric" -> {
                    while (count >= 3) {
                        val v = toNum(readBits(10))
                        if (v >= 1000) throw QRDecodingException("numeric(3) = $v")
                        result.append(v.toString().padStart(3, '0'))
                        count -= 3
                    }
                    if (count == 2) {
                        val v = toNum(readBits(7))
                        if (v >= 100) throw QRDecodingException("numeric(2) = $v")
                        result.append(v.toString().padStart(2, '0'))
                    } else if (count == 1) {
                        val v = toNum(readBits(4))
                        if (v >= 10) throw QRDecodingException("numeric(1) = $v")
                        result.append(v.toString())
                    }
                }

                "alphanumeric" -> {
                    while (count >= 2) {
                        val v = toNum(readBits(11))
                        val chars = QRInfo.alphanumericEncode(listOf(v / 45, v % 45))
                        result.append(chars.joinToString(""))
                        count -= 2
                    }
                    if (count == 1) {
                        val chars = QRInfo.alphanumericEncode(listOf(toNum(readBits(6))))
                        result.append(chars.joinToString(""))
                    }
                }

                "byte" -> {
                    val utf8 = ByteArray(count)
                    for (i in 0 until count) {
                        utf8[i] = toNum(readBits(8)).toByte()
                    }
                    result.append(utf8.toString(Charsets.UTF_8))
                }
            }
        }

        return result.toString()
    }
}
