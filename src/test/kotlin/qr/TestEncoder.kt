package qr

/**
 * Test-only raw QR matrix encoder: a straightforward port of the paulmillr/qr v0.7.2
 * encoder (index.ts) used to build synthetic symbols for decoder tests. Matrices are
 * `Array<BooleanArray>` rows (`[y][x]`), true = dark, quiet-zone border included.
 *
 * The JS encoder packs modules into Int32 words and caches per-version templates for
 * speed; this port works on plain boolean grids with identical output (cross-checked
 * against node in TestEncoderTest / encoder-fixtures.txt).
 */
object TestEncoder {
    enum class Encoding { NUMERIC, ALPHANUMERIC, BYTE }

    private val MODE_BITS = mapOf(Encoding.NUMERIC to 1, Encoding.ALPHANUMERIC to 2, Encoding.BYTE to 4)
    private val LENGTH_BITS = mapOf(
        Encoding.NUMERIC to intArrayOf(10, 12, 14),
        Encoding.ALPHANUMERIC to intArrayOf(9, 11, 13),
        Encoding.BYTE to intArrayOf(8, 16, 16),
    )

    private fun lengthBits(version: Int, enc: Encoding): Int = LENGTH_BITS.getValue(enc)[(version + 7) / 17]

    private fun alnumVal(c: Char): Int = if (c.code < 128) QRInfo.ALPHANUMERIC.indexOf(c) else -1

    /** index.ts detectType L358-366. */
    fun detectType(text: String): Encoding {
        var type = Encoding.NUMERIC
        for (c in text) {
            val v = alnumVal(c)
            if (v < 0) return Encoding.BYTE
            if (v > 9) type = Encoding.ALPHANUMERIC
        }
        return type
    }

    /**
     * Port of `encodeQR(text, 'raw', opts)` (index.ts L1162-1270): dark = true, with border.
     * version null → smallest fitting version; mask null → penalty-score mask selection
     * (index.ts penaltyScore/drawSymbol, first lowest score wins), exactly as JS does;
     * encoding null → detectType. Default border 2 matches JS `opts.border` default.
     * Byte mode uses Kotlin UTF-8 (lone surrogates differ from TextEncoder; not used by tests).
     */
    fun encodeRaw(
        text: String,
        version: Int? = null,
        ecc: ErrorCorrection = ErrorCorrection.MEDIUM,
        mask: Int? = null,
        border: Int = 2,
        encoding: Encoding? = null,
    ): Array<BooleanArray> {
        require(version == null || version in 1..40) { "Invalid version=$version. Expected number [1..40]" }
        require(mask == null || mask in 0..7) { "invalid mask=$mask" }
        require(border > 0) { "invalid border=$border" }
        val enc = encoding ?: detectType(text)
        val ver = version ?: (1..40).firstOrNull { fits(text, it, ecc, enc) }
            ?: throw IllegalArgumentException("Capacity overflow")
        val data = encodeCodewords(text, ver, ecc, enc)
        return withBorder(drawSymbol(ver, ecc, data, mask, test = false), border)
    }

    /**
     * Port of decode.test.ts `symbolImage` (L14-36) minus the final `matrixToImage`: place the given
     * codewords (RS-encoded or deliberately corrupt; bits past the data region are dropped, missing
     * bits stay light) via `drawSymbol(version, ecc, words, mask)` with format/version info drawn, and
     * return the matrix with [border]. Feed the result to `TestImages.matrixToImage(m, scale = 4)`.
     */
    fun symbolMatrix(
        version: Int,
        ecc: ErrorCorrection,
        codewords: IntArray,
        mask: Int = 0,
        border: Int = 4,
    ): Array<BooleanArray> = withBorder(drawSymbol(version, ecc, codewords, mask, test = false), border)

    /** Block layout for a version/ECC level (index.ts `info.capacity` L231-250). */
    data class Capacity(
        val words: Int,       // ECC words per block
        val numBlocks: Int,   // number of blocks
        val shortBlocks: Int, // short block count
        val blockLen: Int,    // data words per short block
        val capacity: Int,    // data bits available
        val total: Int,       // total words (data + ECC)
    )

    fun capacity(version: Int, ecc: ErrorCorrection): Capacity {
        val bytes = QRInfo.BYTES[version - 1]
        val words = QRInfo.WORDS_PER_BLOCK[ecc.ordinal][version - 1]
        val numBlocks = QRInfo.ECC_BLOCKS[ecc.ordinal][version - 1]
        val blockLen = bytes / numBlocks - words
        val shortBlocks = numBlocks - bytes % numBlocks
        return Capacity(
            words, numBlocks, shortBlocks, blockLen,
            capacity = (bytes - words * numBlocks) * 8,
            total = (words + blockLen) * numBlocks + numBlocks - shortBlocks,
        )
    }

    /**
     * Port of `encodeData` (index.ts L377-445): segment bits + terminator + pad codewords, split
     * into RS blocks (short first), and interleave data then ECC. Values are 0..255.
     */
    fun encodeCodewords(text: String, version: Int, ecc: ErrorCorrection, encoding: Encoding): IntArray {
        val cap = capacity(version, ecc)
        val lenBits = lengthBits(version, encoding)
        val utf8 = text.toByteArray(Charsets.UTF_8)
        if (encoding != Encoding.BYTE) {
            val limit = if (encoding == Encoding.NUMERIC) 10 else 45
            for (c in text) {
                val v = alnumVal(c)
                require(v in 0 until limit) { "Unknown letter: \"$c\"" }
            }
        }
        val dataLen = if (encoding == Encoding.BYTE) utf8.size else text.length
        require(dataLen < (1 shl lenBits)) { "Capacity overflow" }
        val bits = ArrayList<Boolean>()
        fun push(value: Int, len: Int) {
            for (i in len - 1 downTo 0) bits.add(((value shr i) and 1) == 1)
        }
        push(MODE_BITS.getValue(encoding), 4)
        push(dataLen, lenBits)
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
                    push(alnumVal(text[i]) * 45 + alnumVal(text[i + 1]), 11)
                    i += 2
                }
                if (dataLen and 1 == 1) push(alnumVal(text[dataLen - 1]), 6)
            }
            Encoding.BYTE -> for (b in utf8) push(b.toInt() and 0xff, 8)
        }
        require(bits.size <= cap.capacity) { "Capacity overflow" }
        val bytes = IntArray(cap.capacity ushr 3)
        for (i in bits.indices) if (bits[i]) bytes[i ushr 3] = bytes[i ushr 3] or (0x80 ushr (i and 7))
        var bitPos = bits.size
        bitPos += minOf(4, cap.capacity - bitPos) // terminator
        if (bitPos and 7 != 0) bitPos += 8 - (bitPos and 7)
        var pad = 0
        for (i in (bitPos ushr 3) until bytes.size) {
            bytes[i] = if (pad == 1) 0x11 else 0xec
            pad = pad xor 1
        }
        val blocks = ArrayList<IntArray>()
        val eccs = ArrayList<IntArray>()
        var pos = 0
        for (i in 0 until cap.numBlocks) {
            val len = cap.blockLen + if (i < cap.shortBlocks) 0 else 1
            val block = bytes.copyOfRange(pos, pos + len)
            blocks.add(block)
            eccs.add(rsEcc(block, cap.words))
            pos += len
        }
        val res = IntArray(bytes.size + cap.words * cap.numBlocks)
        var p = 0
        for (i in 0..cap.blockLen) for (b in blocks) if (i < b.size) res[p++] = b[i]
        for (i in 0 until cap.words) for (e in eccs) res[p++] = e[i]
        return res
    }

    /** index.ts rsEcc L318-336: RS parity of [data] (0..255) with [eccWords] check words. */
    fun rsEcc(data: IntArray, eccWords: Int): IntArray {
        val mul32 = RS.cached(eccWords).mul32
        val stride = mul32.size ushr 8
        val last = stride - 1
        val w = IntArray(stride)
        for (d in data) {
            var cur = w[0]
            val off = (d xor (cur and 0xff)) * stride
            for (k in 0 until last) {
                val next = w[k + 1]
                w[k] = ((cur ushr 8) or (next shl 24)) xor mul32[off + k]
                cur = next
            }
            w[last] = (cur ushr 8) xor mul32[off + last]
        }
        return IntArray(eccWords) { j -> (w[j ushr 2] ushr (8 * (j and 3))) and 0xff }
    }

    // encodeQR's version auto-selection test (index.ts L1218-1224).
    private fun fits(text: String, ver: Int, ecc: ErrorCorrection, enc: Encoding): Boolean {
        val dataLen = if (enc == Encoding.BYTE) text.toByteArray(Charsets.UTF_8).size else text.length
        val encodedBits = when (enc) {
            Encoding.NUMERIC -> dataLen / 3 * 10 + intArrayOf(0, 4, 7)[dataLen % 3]
            Encoding.ALPHANUMERIC -> dataLen / 2 * 11 + (dataLen % 2) * 6
            Encoding.BYTE -> dataLen * 8
        }
        val lb = lengthBits(ver, enc)
        return dataLen < (1 shl lb) && 4 + lb + encodedBits <= capacity(ver, ecc).capacity
    }

    /** Modules per side for a version (index.ts size.encode). */
    internal fun sizeEncode(version: Int): Int = 21 + 4 * (version - 1)

    /** Alignment pattern centre coordinates for a version (index.ts alignmentPatterns). */
    internal fun alignmentPatterns(version: Int): IntArray {
        if (version == 1) return intArrayOf()
        val first = 6
        val last = sizeEncode(version) - first - 1
        val distance = last - first
        val count = Math.ceil(distance / 28.0).toInt()
        var interval = Math.floor(distance / count.toDouble()).toInt()
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

    /** Function-pattern template + function-cell flags (index.ts buildSymCache L695-760). */
    private class Template(val tpl: Array<BooleanArray>, val zigzag: IntArray)

    private fun buildTemplate(ver: Int): Template {
        val size = sizeEncode(ver)
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }
        fun setF(x: Int, y: Int, bit: Boolean) {
            m[y][x] = bit
            fn[y][x] = true
        }
        for ((fx, fy) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
            for (dy in -1 until 8) for (dx in -1 until 8) {
                val x = fx + dx
                val y = fy + dy
                if (x < 0 || y < 0 || x >= size || y >= size) continue
                val on = dx in 0..6 && dy in 0..6 &&
                    (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                setF(x, y, on)
            }
        }
        val align = alignmentPatterns(ver)
        for (ay in align) for (ax in align) {
            if (fn[ay][ax]) continue
            for (dy in -2..2) for (dx in -2..2) setF(ax + dx, ay + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }
        for (i in 0 until size) {
            if (!fn[6][i]) setF(i, 6, i % 2 == 0)
            if (!fn[i][6]) setF(6, i, i % 2 == 0)
        }
        for (i in 0 until 9) {
            if (i != 6) {
                setF(8, i, false)
                setF(i, 8, false)
            }
            if (i < 8) {
                setF(size - 1 - i, 8, false)
                setF(8, size - 1 - i, false)
            }
        }
        if (ver >= 7) {
            for (i in 0 until 18) {
                val x = size - 11 + (i % 3)
                val y = i / 3
                setF(x, y, false)
                setF(y, x, false)
            }
        }
        // Zigzag placement order over non-function cells, packed as y * size + x.
        val order = ArrayList<Int>()
        var xOffset = size - 1
        var dir = -1
        var y = size - 1
        while (xOffset > 0) {
            if (xOffset == 6) xOffset = 5
            while (true) {
                for (j in 0 until 2) {
                    val x = xOffset - j
                    if (!fn[y][x]) order.add(y * size + x)
                }
                if (y + dir < 0 || y + dir >= size) break
                y += dir
            }
            xOffset -= 2
            dir = -dir
        }
        return Template(m, order.toIntArray())
    }

    /** index.ts drawSymbol L814-878 (+ drawInfo L651-693 unless [test]). Returns the bare symbol. */
    fun drawSymbol(ver: Int, ecc: ErrorCorrection, data: IntArray, maskIdx: Int?, test: Boolean = false): Array<BooleanArray> {
        val t = buildTemplate(ver)
        val size = t.tpl.size
        val m = Array(size) { t.tpl[it].copyOf() }
        val need = minOf(8 * data.size, t.zigzag.size)
        for (i in 0 until need) {
            if ((data[i ushr 3] ushr (7 - (i and 7))) and 1 == 1) {
                val p = t.zigzag[i]
                m[p / size][p % size] = true
            }
        }
        fun applyMask(dst: Array<BooleanArray>, mask: Int) {
            for (p in t.zigzag) {
                val x = p % size
                val y = p / size
                if ((QRInfo.maskBits(x, y) ushr mask) and 1 == 1) dst[y][x] = !dst[y][x]
            }
        }
        var mask = maskIdx
        if (mask == null) {
            var best = Int.MAX_VALUE
            for (p in 0 until 8) {
                val cand = Array(size) { m[it].copyOf() }
                applyMask(cand, p)
                val score = penalty(cand)
                if (score < best) {
                    best = score
                    mask = p
                }
            }
        }
        applyMask(m, mask!!)
        if (!test) drawInfo(m, ver, ecc, mask)
        return m
    }

    /** index.ts drawInfo L651-693. */
    private fun drawInfo(m: Array<BooleanArray>, ver: Int, ecc: ErrorCorrection, mask: Int) {
        val size = m.size
        val bits = QRInfo.formatBits(ecc, mask)
        fun set(x: Int, y: Int, bit: Boolean) {
            m[y][x] = bit
        }
        for (i in 0 until 15) {
            val bit = (bits shr i) and 1 == 1
            when {
                i < 6 -> set(8, i, bit)
                i < 8 -> set(8, i + 1, bit)
                i == 8 -> set(7, 8, bit)
                else -> set(14 - i, 8, bit)
            }
            if (i < 8) set(size - 1 - i, 8, bit) else set(8, size - 15 + i, bit)
        }
        set(8, size - 8, true)
        if (ver >= 7) {
            val vbits = QRInfo.versionBits(ver)
            for (i in 0 until 18) {
                val bit = (vbits shr i) and 1 == 1
                val x = size - 11 + (i % 3)
                val y = i / 3
                set(x, y, bit)
                set(y, x, bit)
            }
        }
    }

    /**
     * Mask penalty of a test-form symbol (index.ts penaltyScore L590-649 + runs/finder scans):
     * N1 runs >= 5 score L-2, N2 3 per 2x2 same-colour box, N3 40 per 1011101+4 light finder-like
     * window (both orders), N4 10 per 5% step of dark ratio away from 45..55%; rows and columns.
     */
    internal fun penalty(m: Array<BooleanArray>): Int {
        val size = m.size
        val t = Array(size) { y -> BooleanArray(size) { x -> m[x][y] } }
        var score = 0
        for (g in listOf(m, t)) for (row in g) {
            var run = 1
            for (x in 1..size) {
                if (x < size && row[x] == row[x - 1]) run++
                else {
                    if (run >= 5) score += run - 2
                    run = 1
                }
            }
        }
        var boxes = 0
        var dark = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (m[y][x]) dark++
            if (y < size - 1 && x < size - 1) {
                val c = m[y][x]
                if (m[y][x + 1] == c && m[y + 1][x] == c && m[y + 1][x + 1] == c) boxes++
            }
        }
        val total = size * size
        val darkSteps = (maxOf(0, Math.abs(dark * 100 - total * 50) - total * 5) + total * 5 - 1) / (total * 5)
        score += 3 * boxes + 10 * darkSteps
        val p1 = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val p2 = p1.reversedArray()
        var finders = 0
        for (g in listOf(m, t)) for (row in g) for (x in 0..size - 11) {
            var a = true
            var b = true
            for (k in 0 until 11) {
                if (row[x + k] != p1[k]) a = false
                if (row[x + k] != p2[k]) b = false
            }
            if (a) finders++
            if (b) finders++
        }
        return score + 40 * finders
    }

    private fun withBorder(sym: Array<BooleanArray>, border: Int): Array<BooleanArray> {
        val size = sym.size + 2 * border
        return Array(size) { y ->
            BooleanArray(size) { x ->
                val sx = x - border
                val sy = y - border
                sx in sym.indices && sy in sym.indices && sym[sy][sx]
            }
        }
    }
}
