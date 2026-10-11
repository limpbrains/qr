package qr

// Symbol decode from the sampled module grid (qr v0.7.2 decode.ts `checkVersion` L376-393 and
// `_QRScanner.decodeGrid` / `decodeOriented` L2182-2483). Grid values are 0/1 Ints (JS Uint8Array).

internal object GridDecoder {
    /**
     * decode.ts L376-393: Version 7+ carries two redundant BCH words; at least one must identify
     * the version implied by the sampled dimension within its radius-3 budget.
     */
    fun checkVersion(m: IntArray, size: Int): Boolean {
        val ver = (size - 17) / 4
        if (ver < 7) return true
        var v1 = 0
        var v2 = 0
        for (i in 0 until 18) {
            val x = size - 11 + (i % 3)
            val y = i / 3
            v1 = v1 or (m[y * size + x] shl i)
            v2 = v2 or (m[x * size + y] shl i)
        }
        val expected = QRInfo.versionBits(ver)
        val d1 = QRInfo.popcnt(expected xor v1)
        val d2 = QRInfo.popcnt(expected xor v2)
        return d1 <= 3 || d2 <= 3
    }
}

/**
 * decode.ts L2182-2196: decode the grid; on failure retry once on the in-place transposed grid
 * (a mirror image locates like any symbol and fails only at format/data bits). When both
 * orientations fail, [QRScanner.fail] is the FIRST orientation's tag, as JS returns `result`.
 */
internal fun QRScanner.decodeGrid(size: Int): String? {
    decodeOriented(size)?.let { return it }
    val first = fail
    val grid = grid
    for (y in 1 until size)
        for (x in 0 until y) {
            val a = y * size + x
            val b = x * size + y
            val t = grid[a]
            grid[a] = grid[b]
            grid[b] = t
        }
    decodeOriented(size)?.let { return it }
    fail = first
    return null
}

/**
 * decode.ts L2198-2483: format words (both copies, up to two distinct in-radius candidates),
 * function map, masked zigzag read, deinterleave, per-block Reed-Solomon and payload parse.
 * Null with [QRScanner.fail] = the last candidate's failure (`format` when none is in radius).
 *
 * Scratch lifetimes (shared arenas, as in JS): `tmp8` is first the alignment-position prefix,
 * then the function map, then (after extraction, its final map read) RS polynomials in its
 * prefix and deinterleaved blocks in its tail; `codewords` holds the interleaved stream, then
 * the corrected data bytes.
 */
internal fun QRScanner.decodeOriented(size: Int): String? {
    var decoded: String? = null
    var failed = Fail.FORMAT
    if (!GridDecoder.checkVersion(grid, size)) failed = Fail.VERSION
    else {
        val m = grid
        var f1 = 0
        var f2 = 0
        for (i in 0 until 15) {
            val b1 = if (i < 6) m[i * size + 8]
            else if (i < 8) m[(i + 1) * size + 8]
            else if (i == 8) m[8 * size + 7]
            else m[8 * size + 14 - i]
            val b2 = if (i < 8) m[8 * size + size - 1 - i] else m[(size - 15 + i) * size + 8]
            f1 = f1 or (b1 shl i)
            f2 = f2 or (b2 shl i)
        }
        // Last in-radius (ecc,mask) wins per copy, matching the original overwrite scan.
        var aEcc = -1
        var bEcc = -1
        var aMask = 0
        var bMask = 0
        var aDistance = 0
        var bDistance = 0
        val levels = ErrorCorrection.entries // JS ECC_LEVELS order: low, medium, quartile, high
        for (ecc in levels.indices)
            for (mask in 0 until 8) {
                val bits = QRInfo.formatBits(levels[ecc], mask)
                val d1 = QRInfo.popcnt(bits xor f1)
                val d2 = QRInfo.popcnt(bits xor f2)
                if (d1 <= 3) {
                    aEcc = ecc
                    aMask = mask
                    aDistance = d1
                }
                if (d2 <= 3) {
                    bEcc = ecc
                    bMask = mask
                    bDistance = d2
                }
            }
        val same = aEcc == bEcc && aMask == bMask
        // Store copy B first when it is the only candidate or strictly closer.
        val firstB = bEcc >= 0 && (aEcc < 0 || (!same && bDistance < aDistance))
        val aFormat = if (aEcc < 0) -1 else (aEcc shl 3) or aMask
        val bFormat = if (bEcc < 0) -1 else (bEcc shl 3) or bMask
        val first = if (firstB) bFormat else aFormat
        val second = if (firstB) aFormat else if (bEcc >= 0 && !same) bFormat else -1
        var formatValue = first
        while (formatValue >= 0) {
            decoded = decodeFormat(size, formatValue)
            if (decoded == null) failed = fail
            else break
            formatValue = if (formatValue == first) second else -1
        }
    }
    if (decoded == null) fail = failed
    return decoded
}

// The labelled `format:` block of decodeOriented (L2247-2475) for one (ecc << 3 | mask) value.
private fun QRScanner.decodeFormat(size: Int, formatValue: Int): String? {
    val eccIndex = formatValue shr 3
    val mask = formatValue and 7
    val ver = (size - 17) / 4
    val fn = tmp8
    fn.fill(0, 0, size * size)
    for (finder in 0 until 3) {
        val fx = if (finder == 1) size - 7 else 0
        val fy = if (finder == 2) size - 7 else 0
        for (dy in -1 until 8)
            for (dx in -1 until 8) {
                val x = fx + dx
                val y = fy + dy
                if (x >= 0 && y >= 0 && x < size && y < size) fn[y * size + x] = 1
            }
    }
    // Positions land in fn[0..count] (finder cells, already 1): see setAlignments.
    val count = setAlignments(ver)
    val align = tmp8
    for (yi in 0 until count)
        for (xi in 0 until count) {
            val ax = align[xi]
            val ay = align[yi]
            if (fn[ay * size + ax] != 0) continue
            for (dy in -2..2)
                for (dx in -2..2) fn[(ay + dy) * size + ax + dx] = 1
        }
    for (i in 0 until size) {
        fn[6 * size + i] = 1
        fn[i * size + 6] = 1
    }
    for (i in 0..8) {
        fn[i * size + 8] = 1
        fn[8 * size + i] = 1
        if (i < 8) {
            fn[8 * size + size - 1 - i] = 1
            fn[(size - 1 - i) * size + 8] = 1
        }
    }
    if (ver >= 7)
        for (i in 0 until 18) {
            val x = size - 11 + (i % 3)
            val y = i / 3
            fn[y * size + x] = 1
            fn[x * size + y] = 1
        }
    val bytes = codewords
    val total = QRInfo.BYTES[ver - 1]
    val limit = 8 * total
    val grid = grid
    var bit = 0
    var acc = 0
    var dir = -1
    var y = size - 1
    var xOffset = size - 1
    while (xOffset > 0) {
        if (xOffset == 6) xOffset = 6 - 1
        // Mask predicates repeat every 12 rows: pack one period per column into a word.
        var mask0 = 0
        var mask1 = 0
        for (i in 0 until 12) {
            mask0 = mask0 or (((QRInfo.maskBits(xOffset, i) shr mask) and 1) shl i)
            mask1 = mask1 or (((QRInfo.maskBits(xOffset - 1, i) shr mask) and 1) shl i)
        }
        var ym = y % 12
        while (true) {
            val row = y * size
            for (j in 0 until 2) {
                val x = xOffset - j
                if (fn[row + x] != 0) continue
                // Codewords fill in walk order, so each byte lands whole after its eighth bit.
                if (bit < limit) {
                    acc = (acc shl 1) or (grid[row + x] xor (((if (j != 0) mask1 else mask0) shr ym) and 1))
                    if ((bit and 7) == 7) {
                        bytes[bit shr 3] = acc
                        acc = 0
                    }
                }
                bit++
            }
            if (y + dir < 0 || y + dir >= size) break
            y += dir
            ym += dir
            if (ym < 0) ym = 11
            else if (ym == 12) ym = 0
        }
        dir = -dir
        xOffset -= 2
    }
    val ecc = eccIndex // ECC_LEVELS[eccIndex]; spec tables are indexed by that level index
    val words = QRInfo.WORDS_PER_BLOCK[ecc][ver - 1]
    val blocks = QRInfo.ECC_BLOCKS[ecc][ver - 1]
    val shortLen = total / blocks - words
    val shortBlocks = blocks - (total % blocks)
    // Extraction is tmp8's final function-map read. Its prefix now holds RS polynomials
    // (RS.correct slots: syndromes, sigma, previous, next) and its dead tail deinterleaved blocks.
    val blockBase = 4 * words + 3
    val blockLen = shortLen + words
    val blockBytes = fn
    var pos = 0
    // QR interleaving writes every data column before every ECC column.
    for (eccPhase in 0 until 2)
        for (i in 0 until (if (eccPhase != 0) words else shortLen + 1))
            for (block in 0 until blocks) {
                val length = blockLen + (if (block >= shortBlocks) 1 else 0)
                if (eccPhase == 0 && i >= length - words) continue
                val offset = blockBase + block * blockLen + Math.max(0, block - shortBlocks)
                blockBytes[offset + (if (eccPhase != 0) length - words else 0) + i] = bytes[pos++]
            }
    val dataLen = total - words * blocks
    // Deinterleaving copied every codeword into blockBytes, so corrected payload bytes replace
    // the now-dead interleaved stream in the same arena.
    val data = bytes
    pos = 0
    for (block in 0 until blocks) {
        val offset = blockBase + block * blockLen + Math.max(0, block - shortBlocks)
        val length = blockLen + (if (block >= shortBlocks) 1 else 0)
        if (!RS.correct(blockBytes, offset, length, words, fn, remainder)) {
            fail = Fail.RS
            return null
        }
        val end = offset + length - words
        for (i in offset until end) data[pos++] = blockBytes[i]
    }
    val text = payload.decode(data, dataLen, ver)
    if (text == null) fail = Fail.DATA
    return text
}
