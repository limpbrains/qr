package qr

/** Segment bit encoding, capacity tables and RS block interleaving (index.ts L249-445, L898-904). */
internal object Segments {
    // index.ts MODE_BITS L249, indexed by Encoding.ordinal.
    val MODE_BITS = intArrayOf(1, 2, 4)

    // index.ts LENGTH_BITS L250-254, indexed [Encoding.ordinal][(ver + 7) / 17].
    val LENGTH_BITS = arrayOf(
        intArrayOf(10, 12, 14),
        intArrayOf(9, 11, 13),
        intArrayOf(8, 16, 16),
    )

    fun lengthBits(ver: Int, encoding: Encoding): Int = LENGTH_BITS[encoding.ordinal][(ver + 7) / 17]

    // index.ts ALNUM_VAL L368-375: charCode -> alphanumeric value, -1 outside the Table 5 alphabet.
    val ALNUM_VAL = IntArray(128) { -1 }.also { t ->
        for (i in QRInfo.ALPHANUMERIC.indices) t[QRInfo.ALPHANUMERIC[i].code] = i
    }

    // index.ts detectType L358-366: per UTF-16 unit; >= 128 or outside the alphabet -> byte.
    fun detectType(text: String): Encoding {
        var type = Encoding.NUMERIC
        for (i in text.indices) {
            val c = text[i].code
            val v = if (c < 128) ALNUM_VAL[c] else -1
            if (v < 0) return Encoding.BYTE
            if (v > 9) type = Encoding.ALPHANUMERIC
        }
        return type
    }

    /** Block layout for a version/ECC level (index.ts capacity L338-345; [total] is Kotlin-only). */
    data class Capacity(
        val words: Int,       // ECC words per block
        val numBlocks: Int,   // number of blocks
        val shortBlocks: Int, // short block count
        val blockLen: Int,    // data words per short block
        val capacity: Int,    // data bits available
        val total: Int,       // total words (data + ECC)
    )

    // index.ts L338-345
    fun capacity(ver: Int, ecc: ErrorCorrection): Capacity {
        val bytes = QRInfo.BYTES[ver - 1]
        val words = QRInfo.WORDS_PER_BLOCK[ecc.ordinal][ver - 1]
        val numBlocks = QRInfo.ECC_BLOCKS[ecc.ordinal][ver - 1]
        val blockLen = bytes / numBlocks - words
        val shortBlocks = numBlocks - bytes % numBlocks
        return Capacity(
            words, numBlocks, shortBlocks, blockLen,
            capacity = (bytes - words * numBlocks) * 8,
            total = (words + blockLen) * numBlocks + numBlocks - shortBlocks,
        )
    }

    // index.ts byteCapacity L898-904
    fun byteCapacity(ver: Int, ecc: ErrorCorrection): Int {
        val lengthBits = lengthBits(ver, Encoding.BYTE)
        return minOf((1 shl lengthBits) - 1, (capacity(ver, ecc).capacity - 4 - lengthBits) / 8)
    }

    /**
     * Segment bits + terminator + padding + RS interleaving (index.ts encodeData L377-445).
     * Values are 0..255. [utf8] is required for [Encoding.BYTE] and ignored otherwise.
     * Precondition: [text] must already be valid for [encoding] (validated by `QREncoder.prepare`);
     * JS coerces invalid characters to 0, Kotlin may throw.
     */
    fun encodeData(ver: Int, ecc: ErrorCorrection, text: String, encoding: Encoding, utf8: ByteArray?): IntArray {
        val cap = capacity(ver, ecc)
        val lengthBits = lengthBits(ver, encoding)
        val dataLen = if (encoding == Encoding.BYTE) utf8!!.size else text.length
        if (dataLen >= 1 shl lengthBits) throw IllegalArgumentException("Capacity overflow")
        val bytes = IntArray(cap.capacity ushr 3)
        // L387-399: MSB-first bit accumulator flushed a byte at a time; pushes are <= 16 bits.
        // Stores past the end are dropped like JS typed-array OOB writes; bitPos stays exact.
        var acc = 0
        var accBits = 0
        var bytePos = 0
        fun push(value: Int, len: Int) {
            acc = (acc shl len) or value
            accBits += len
            while (accBits >= 8) {
                accBits -= 8
                if (bytePos < bytes.size) bytes[bytePos] = (acc ushr accBits) and 0xff
                bytePos++
            }
        }
        // L400-413
        push(MODE_BITS[encoding.ordinal], 4)
        push(dataLen, lengthBits)
        when (encoding) {
            Encoding.NUMERIC -> {
                var i = 0
                while (i < dataLen) {
                    val n = minOf(3, dataLen - i)
                    push(text.substring(i, i + n).toInt(), intArrayOf(0, 4, 7, 10)[n])
                    i += 3
                }
            }
            Encoding.ALPHANUMERIC -> {
                var i = 0
                while (i + 1 < dataLen) {
                    push(ALNUM_VAL[text[i].code] * 45 + ALNUM_VAL[text[i + 1].code], 11)
                    i += 2
                }
                if (dataLen and 1 != 0) push(ALNUM_VAL[text[dataLen - 1].code], 6)
            }
            Encoding.BYTE -> for (b in utf8!!) push(b.toInt() and 0xff, 8)
        }
        // L414-421
        var bitPos = bytePos * 8 + accBits
        if (bitPos > cap.capacity) throw IllegalArgumentException("Capacity overflow")
        if (accBits != 0 && bytePos < bytes.size) bytes[bytePos] = (acc shl (8 - accBits)) and 0xff
        bitPos += minOf(4, cap.capacity - bitPos)
        if (bitPos and 7 != 0) bitPos += 8 - (bitPos and 7)
        var pad = 0
        for (i in (bitPos ushr 3) until bytes.size) {
            bytes[i] = if (pad != 0) 0x11 else 0xec
            pad = pad xor 1
        }
        // L422-444: split into RS blocks (short first), parity, interleave data then ECC.
        val blocks = ArrayList<IntArray>(cap.numBlocks)
        val eccs = ArrayList<IntArray>(cap.numBlocks)
        var pos = 0
        for (i in 0 until cap.numBlocks) {
            val len = cap.blockLen + if (i < cap.shortBlocks) 0 else 1
            val block = bytes.copyOfRange(pos, pos + len)
            blocks.add(block)
            eccs.add(RS.encode(block, cap.words))
            pos += len
        }
        val res = IntArray(bytes.size + cap.words * cap.numBlocks)
        var p = 0
        for (i in 0..cap.blockLen) for (b in blocks) if (i < b.size) res[p++] = b[i]
        for (i in 0 until cap.words) for (e in eccs) res[p++] = e[i]
        return res
    }
}
