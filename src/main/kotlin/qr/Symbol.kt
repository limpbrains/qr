package qr

import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Symbol layout and drawing (index.ts alignmentPatterns L221-233, drawInfo L651-693,
 * buildSymCache L695-760, drawSymbol L814-878). Matrices are `Array<BooleanArray>` rows (`[y][x]`),
 * true = dark; the JS packs modules into Int32 words, which changes nothing observable.
 */
internal object Symbol {
    /** Modules per side for a version (index.ts size.encode). */
    fun size(ver: Int): Int = 21 + 4 * (ver - 1)

    /** Alignment pattern centre coordinates (index.ts L221-233). */
    fun alignmentPatterns(ver: Int): IntArray {
        if (ver == 1) return intArrayOf()
        val last = 21 + 4 * (ver - 1) - 7
        val count = (last - 6 + 27) / 28 // Math.ceil((last - 6) / 28)
        var interval = (last - 6) / count
        if (interval % 2 != 0) interval += 1
        else if (((last - 6) % count) * 2 >= count) interval += 2
        val res = IntArray(count + 1)
        res[0] = 6
        for (m in 1 until count) res[m] = last - (count - m) * interval
        res[count] = last
        return res
    }

    /**
     * Per-version layout (index.ts SymCache): [tpl] holds function patterns with format/version/dark
     * cells reserved light (the mask-scoring "test form"), [zigzag] the data placement order as
     * `y * size + x` over the non-function cells. Never mutated after construction.
     */
    class Template(val tpl: Array<BooleanArray>, val zigzag: IntArray)

    // JS keeps a single-slot symCache; a per-version slot is equivalent and thread-safe
    // (racing builds produce identical immutable templates).
    private val cache = AtomicReferenceArray<Template>(40)

    fun template(ver: Int): Template {
        cache.get(ver - 1)?.let { return it }
        val t = buildTemplate(ver)
        cache.compareAndSet(ver - 1, null, t)
        return cache.get(ver - 1)
    }

    // index.ts buildSymCache L695-760
    private fun buildTemplate(ver: Int): Template {
        val size = size(ver)
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }
        fun setF(x: Int, y: Int, bit: Boolean) {
            m[y][x] = bit
            fn[y][x] = true
        }
        // Finder patterns + separators (clipped at the edges).
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
        // Alignment patterns (skip those overlapping finders).
        val align = alignmentPatterns(ver)
        for (ay in align) for (ax in align) {
            if (fn[ay][ax]) continue
            for (dy in -2..2) for (dx in -2..2) setF(ax + dx, ay + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }
        // Timing patterns.
        for (i in 0 until size) {
            if (!fn[6][i]) setF(i, 6, i % 2 == 0)
            if (!fn[i][6]) setF(6, i, i % 2 == 0)
        }
        // Reserve format / version / dark-module cells light.
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
        // Zigzag placement order over non-function cells.
        val order = IntArray(size * size)
        var n = 0
        var xOffset = size - 1
        var dir = -1
        var y = size - 1
        while (xOffset > 0) {
            if (xOffset == 6) xOffset = 5 // skip the vertical timing column
            while (true) {
                for (j in 0 until 2) {
                    val x = xOffset - j
                    if (!fn[y][x]) order[n++] = y * size + x
                }
                if (y + dir < 0 || y + dir >= size) break
                y += dir
            }
            xOffset -= 2
            dir = -dir
        }
        return Template(m, order.copyOf(n))
    }

    /**
     * index.ts drawSymbol L814-878: template copy, data bits MSB-first along the zigzag (trailing
     * remainder bits stay light), mask selection when [mask] is null (score all 8 on the test form,
     * first lowest wins), then format/version info unless [test]. Returns the bare symbol.
     */
    fun draw(ver: Int, ecc: ErrorCorrection, data: IntArray, mask: Int?, test: Boolean = false): Array<BooleanArray> {
        val t = template(ver)
        val size = t.tpl.size
        val zigzag = t.zigzag
        val m = Array(size) { t.tpl[it].copyOf() }
        val need = minOf(8 * data.size, zigzag.size)
        for (i in 0 until need) {
            if ((data[i ushr 3] ushr (7 - (i and 7))) and 1 == 1) {
                val p = zigzag[i]
                m[p / size][p % size] = true
            }
        }
        fun applyMask(dst: Array<BooleanArray>, mask: Int) {
            for (p in zigzag) {
                val x = p % size
                val y = p / size
                if ((QRInfo.maskBits(x, y) ushr mask) and 1 == 1) dst[y][x] = !dst[y][x]
            }
        }
        var best = mask
        if (best == null) {
            var bestScore = Int.MAX_VALUE
            for (p in 0 until 8) {
                val cand = Array(size) { m[it].copyOf() }
                applyMask(cand, p)
                val score = Penalty.score(cand)
                if (score < bestScore) {
                    bestScore = score
                    best = p
                }
            }
        }
        applyMask(m, best!!)
        if (!test) drawInfo(m, ver, ecc, best)
        return m
    }

    // index.ts drawInfo L651-693
    fun drawInfo(m: Array<BooleanArray>, ver: Int, ecc: ErrorCorrection, mask: Int) {
        val size = m.size
        val bits = QRInfo.formatBits(ecc, mask)
        fun set(x: Int, y: Int, bit: Boolean) {
            m[y][x] = bit
        }
        for (i in 0 until 15) {
            val bit = (bits shr i) and 1 == 1
            // Copy 1 around the top-left finder (skipping the timing row/column).
            when {
                i < 6 -> set(8, i, bit)
                i < 8 -> set(8, i + 1, bit)
                i == 8 -> set(7, 8, bit)
                else -> set(14 - i, 8, bit)
            }
            // Copy 2 under the top-right / right of the bottom-left finder.
            if (i < 8) set(size - 1 - i, 8, bit) else set(8, size - 15 + i, bit)
        }
        set(8, size - 8, true) // dark module
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

    /** Symbol surrounded by [border] light modules on every side. */
    fun withBorder(m: Array<BooleanArray>, border: Int): Array<BooleanArray> {
        val size = m.size + 2 * border
        return Array(size) { y ->
            BooleanArray(size) { x ->
                val sx = x - border
                val sy = y - border
                sx in m.indices && sy in m.indices && m[sy][sx]
            }
        }
    }
}
