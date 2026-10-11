package qr

/**
 * QR code constants, capacity tables, and utility functions.
 */
object QRInfo {

    /**
     * Total codewords per version, indexed by `version - 1` (index.ts L164-176, ISO/IEC 18004 Table 1).
     */
    internal val BYTES = intArrayOf(
        26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655, 733, 815, 901, 991, 1085,
        1156, 1258, 1364, 1474, 1588, 1706, 1828, 1921, 2051, 2185, 2323, 2465, 2611, 2761, 2876, 3034, 3196, 3362, 3532, 3706
    )

    /**
     * ECC codewords per block (index.ts L181-198), indexed `[ecc.ordinal][version - 1]`.
     * [ErrorCorrection] ordinal order LOW, MEDIUM, QUARTILE, HIGH equals JS `ECC_LEVELS`
     * (index.ts L178), so `ecc.ordinal` is the JS ECC level index for every spec table here.
     */
    internal val WORDS_PER_BLOCK: Array<IntArray> = arrayOf(
        intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
    )

    /**
     * Error-correction block count (index.ts L201-206), indexed `[ecc.ordinal][version - 1]`.
     */
    internal val ECC_BLOCKS: Array<IntArray> = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81)
    )

    /**
     * Alphanumeric characters in value order (index.ts L212, ISO/IEC 18004 Table 5).
     */
    const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

    // index.ts L446-462
    private fun maskCalc(x: Int, y: Int): Int {
        val x2 = x % 2
        val y2 = y % 2
        val x3 = x % 3
        val xy3 = (x3 * (y % 3)) % 3
        val xy2 = x2 and y2
        var bits = 0
        if (x2 == y2) bits = bits or 1
        if (y2 == 0) bits = bits or 2
        if (x3 == 0) bits = bits or 4
        if ((x + y) % 3 == 0) bits = bits or 8
        if ((y / 2 + x / 3) % 2 == 0) bits = bits or 16
        if (xy2 + xy3 == 0) bits = bits or 32
        if ((xy2 + xy3) % 2 == 0) bits = bits or 64
        if (((x2 xor y2) + xy3) % 2 == 0) bits = bits or 128
        return bits
    }

    /**
     * Mask predicate vectors: every predicate repeats every 6 columns and 12 rows,
     * so all 8 are precomputed into a 72-entry table (index.ts L465-469).
     */
    private val MASK_TABLE = IntArray(72).also { t ->
        for (y in 0 until 12) for (x in 0 until 6) t[y * 6 + x] = maskCalc(x, y)
    }

    /**
     * 8-bit vector of all mask predicates at module (x, y): bit k is set when mask k
     * inverts that module (index.ts L470-472). x, y must be non-negative.
     */
    fun maskBits(x: Int, y: Int): Int = MASK_TABLE[(y % 12) * 6 + (x % 6)]

    /**
     * Number of set bits in [x].
     */
    fun popcnt(x: Int): Int = Integer.bitCount(x)

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
}
