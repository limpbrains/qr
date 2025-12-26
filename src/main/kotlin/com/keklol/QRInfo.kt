package com.keklol

import kotlin.math.ceil
import kotlin.math.floor

/**
 * QR code constants, capacity tables, and utility functions.
 */
object QRInfo {

    // Total bytes per version (1-40)
    private val BYTES = intArrayOf(
        26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655, 733, 815, 901, 991, 1085,
        1156, 1258, 1364, 1474, 1588, 1706, 1828, 1921, 2051, 2185, 2323, 2465, 2611, 2761, 2876, 3034, 3196, 3362, 3532, 3706
    )

    // ECC words per block for each error correction level
    private val WORDS_PER_BLOCK = mapOf(
        ErrorCorrection.LOW to intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        ErrorCorrection.MEDIUM to intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        ErrorCorrection.QUARTILE to intArrayOf(13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        ErrorCorrection.HIGH to intArrayOf(17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
    )

    // Number of ECC blocks per version
    private val ECC_BLOCKS = mapOf(
        ErrorCorrection.LOW to intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        ErrorCorrection.MEDIUM to intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        ErrorCorrection.QUARTILE to intArrayOf(1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        ErrorCorrection.HIGH to intArrayOf(1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81)
    )

    // Mask patterns
    val PATTERNS: List<(Int, Int) -> Boolean> = listOf(
        { x, y -> (x + y) % 2 == 0 },
        { _, y -> y % 2 == 0 },
        { x, _ -> x % 3 == 0 },
        { x, y -> (x + y) % 3 == 0 },
        { x, y -> (floor(y / 2.0).toInt() + floor(x / 3.0).toInt()) % 2 == 0 },
        { x, y -> ((x * y) % 2) + ((x * y) % 3) == 0 },
        { x, y -> (((x * y) % 2) + ((x * y) % 3)) % 2 == 0 },
        { x, y -> (((x + y) % 2) + ((x * y) % 3)) % 2 == 0 }
    )

    // Alphanumeric character set
    private const val ALPHANUMERIC_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

    /**
     * Convert version to size (modules per side).
     */
    fun sizeEncode(version: Int): Int = 21 + 4 * (version - 1)

    /**
     * Convert size to version.
     */
    fun sizeDecode(size: Int): Int = (size - 17) / 4

    /**
     * Get the size type (0, 1, or 2) for a version.
     */
    fun sizeType(version: Int): Int = floor((version + 7) / 17.0).toInt()

    /**
     * Validate that a version number is valid (1-40).
     */
    fun validateVersion(version: Int) {
        if (version < 1 || version > 40) {
            throw InvalidVersionException("Invalid version=$version. Expected [1..40]")
        }
    }

    /**
     * Get alignment pattern positions for a version.
     */
    fun alignmentPatterns(version: Int): IntArray {
        if (version == 1) return intArrayOf()
        val first = 6
        val last = sizeEncode(version) - first - 1
        val distance = last - first
        val count = ceil(distance / 28.0).toInt()
        var interval = floor(distance / count.toDouble()).toInt()
        if (interval % 2 != 0) {
            interval += 1
        } else if ((distance % count) * 2 >= count) {
            interval += 2
        }
        val res = mutableListOf(first)
        for (m in 1 until count) {
            res.add(last - (count - m) * interval)
        }
        res.add(last)
        return res.toIntArray()
    }

    private const val FORMAT_MASK = 0b101010000010010

    /**
     * Calculate format bits for error correction level and mask.
     */
    fun formatBits(ecc: ErrorCorrection, maskIdx: Int): Int {
        val data = (ecc.code shl 3) or maskIdx
        var d = data
        for (i in 0 until 10) {
            d = (d shl 1) xor ((d shr 9) * 0b10100110111)
        }
        return ((data shl 10) or d) xor FORMAT_MASK
    }

    /**
     * Calculate version bits for a version (7+).
     */
    fun versionBits(version: Int): Int {
        var d = version
        for (i in 0 until 12) {
            d = (d shl 1) xor ((d shr 11) * 0b1111100100101)
        }
        return (version shl 12) or d
    }

    /**
     * Get the number of length bits for a given version and encoding type.
     */
    fun lengthBits(version: Int, type: EncodingType): Int {
        val table = when (type) {
            EncodingType.NUMERIC -> intArrayOf(10, 12, 14)
            EncodingType.ALPHANUMERIC -> intArrayOf(9, 11, 13)
            EncodingType.BYTE -> intArrayOf(8, 16, 16)
            EncodingType.KANJI -> intArrayOf(8, 10, 12)
            EncodingType.ECI -> intArrayOf(0, 0, 0)
        }
        return table[sizeType(version)]
    }

    /**
     * Decode alphanumeric character to index.
     */
    fun alphanumericDecode(chars: List<Char>): List<Int> =
        chars.map { ALPHANUMERIC_CHARS.indexOf(it) }

    /**
     * Encode indices to alphanumeric characters.
     */
    fun alphanumericEncode(indices: List<Int>): List<Char> =
        indices.map { ALPHANUMERIC_CHARS[it] }

    /**
     * Capacity information for a version and error correction level.
     */
    data class Capacity(
        val words: Int,       // ECC words per block
        val numBlocks: Int,   // Number of data blocks
        val shortBlocks: Int, // Short block count
        val blockLen: Int,    // Data length per block
        val capacity: Int,    // Total bits available
        val total: Int        // Total bytes (data + ECC)
    )

    /**
     * Calculate capacity for a version and error correction level.
     */
    fun capacity(version: Int, ecc: ErrorCorrection): Capacity {
        val bytes = BYTES[version - 1]
        val words = WORDS_PER_BLOCK[ecc]!![version - 1]
        val numBlocks = ECC_BLOCKS[ecc]!![version - 1]
        val blockLen = floor(bytes / numBlocks.toDouble()).toInt() - words
        val shortBlocks = numBlocks - (bytes % numBlocks)
        return Capacity(
            words = words,
            numBlocks = numBlocks,
            shortBlocks = shortBlocks,
            blockLen = blockLen,
            capacity = (bytes - words * numBlocks) * 8,
            total = (words + blockLen) * numBlocks + numBlocks - shortBlocks
        )
    }

    /**
     * Draw the QR code template (finder patterns, timing patterns, format/version info).
     */
    fun drawTemplate(version: Int, ecc: ErrorCorrection, maskIdx: Int, test: Boolean = false): Bitmap {
        val size = sizeEncode(version)
        var b = Bitmap(size + 2, size + 2)

        // Create finder pattern (7x7)
        val finder = Bitmap(3, 3)
            .rect(0, 0, 3, 3, true)
            .border(1, false)
            .border(1, true)
            .border(1, false)

        // Embed finder patterns
        b.embed(0, 0, finder)                           // top left
        b.embed(size + 2 - finder.width, 0, finder)     // top right
        b.embed(0, size + 2 - finder.height, finder)    // bottom left

        // Slice to actual size
        b = b.slice(1, 1, size, size)

        // Alignment patterns
        val align = Bitmap(1, 1)
            .rect(0, 0, 1, 1, true)
            .border(1, false)
            .border(1, true)
        val alignPos = alignmentPatterns(version)
        for (y in alignPos) {
            for (x in alignPos) {
                if (b.get(x, y) != null) continue
                b.embed(x - 2, y - 2, align)
            }
        }

        // Timing patterns
        b.hLine(0, 6, size) { x, cur -> if (cur == null) x % 2 == 0 else cur }
        b.vLine(6, 0, size) { y, cur -> if (cur == null) y % 2 == 0 else cur }

        // Format information
        val bits = formatBits(ecc, maskIdx)
        fun getBit(i: Int): Boolean = !test && ((bits shr i) and 1) == 1

        // Vertical format bits (right of top-left finder)
        for (i in 0 until 6) b.set(8, i, getBit(i))
        for (i in 6 until 8) b.set(8, i + 1, getBit(i))
        for (i in 8 until 15) b.set(8, size - 15 + i, getBit(i))

        // Horizontal format bits (under top-right finder)
        for (i in 0 until 8) b.set(size - i - 1, 8, getBit(i))
        for (i in 8 until 9) b.set(15 - i - 1 + 1, 8, getBit(i))
        for (i in 9 until 15) b.set(15 - i - 1, 8, getBit(i))
        b.set(8, size - 8, !test) // dark module

        // Version information (for version >= 7)
        if (version >= 7) {
            val vBits = versionBits(version)
            for (i in 0 until 18) {
                val bit = !test && ((vBits shr i) and 1) == 1
                val x = floor(i / 3.0).toInt()
                val y = (i % 3) + size - 8 - 3
                b.set(y, x, bit)
                b.set(x, y, bit)
            }
        }

        return b
    }

    /**
     * Iterate over data positions in zigzag order.
     */
    inline fun zigzag(tpl: Bitmap, maskIdx: Int, fn: (x: Int, y: Int, mask: Boolean) -> Unit) {
        val size = tpl.height
        val pattern = PATTERNS[maskIdx]
        var dir = -1
        var y = size - 1

        var xOffset = size - 1
        while (xOffset > 0) {
            if (xOffset == 6) xOffset = 5 // skip vertical timing pattern

            while (true) {
                for (j in 0 until 2) {
                    val x = xOffset - j
                    if (tpl.get(x, y) == null) {
                        fn(x, y, pattern(x, y))
                    }
                }
                if (y + dir < 0 || y + dir >= size) break
                y += dir
            }
            dir = -dir
            xOffset -= 2
        }
    }
}
