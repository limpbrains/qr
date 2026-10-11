package qr

/**
 * Reed-Solomon over [GF256] as in qr v0.7.2: generator tables (index.ts L276-312) and the
 * per-block Berlekamp-Massey / Chien / Forney corrector from `decodeOriented` (decode.ts L2369-2468).
 * Bytes are Ints 0..255 (JS Uint8Array values); Int arithmetic matches JS Int32 bit-for-bit.
 */
internal object RS {
    /**
     * Generator polynomial (leading 1 dropped) and every coefficient*feedback product, packed four
     * to an Int: `mul32[f * stride + (j ushr 2)]` holds `gen[j] * f` in byte `j and 3`.
     */
    class Generator(val gen: IntArray, val mul32: IntArray, val stride: Int)

    // JS RS_CACHE (index.ts L291), indexed by eccWords. Racing fills build identical immutable
    // generators, and final-field publication makes the plain array safe across threads.
    private val cache = arrayOfNulls<Generator>(256)

    /** Minimum [correct] `tmp` size for [words] ECC words: syndromes, sigma, previous, next slots. */
    fun tmpSize(words: Int): Int = 4 * words + 3

    // index.ts L276-288
    private fun rsGenerator(eccWords: Int): IntArray {
        val exp = GF256.EXP
        val log = GF256.LOG
        val gen = IntArray(eccWords) // coefficients after the leading x^n
        gen[eccWords - 1] = 1
        var root = 1
        for (i in 0 until eccWords) {
            for (j in 0 until eccWords) {
                val c = gen[j]
                gen[j] = (if (c != 0) exp[log[c] + log[root]] else 0) xor
                    (if (j + 1 < eccWords) gen[j + 1] else 0)
            }
            root = exp[log[root] + 1] // next power of alpha
        }
        return gen
    }

    /** Cached generator tables for [eccWords] (index.ts L297-312). */
    fun cached(eccWords: Int): Generator {
        cache[eccWords]?.let { return it }
        val gen = rsGenerator(eccWords)
        val exp = GF256.EXP
        val log = GF256.LOG
        val stride = (eccWords + 3) ushr 2
        val mul32 = IntArray(256 * stride)
        for (f in 1 until 256) {
            val lf = log[f]
            for (j in 0 until eccWords) {
                val c = gen[j]
                if (c != 0) {
                    val idx = f * stride + (j ushr 2)
                    mul32[idx] = mul32[idx] or (exp[log[c] + lf] shl (8 * (j and 3)))
                }
            }
        }
        val res = Generator(gen, mul32, stride)
        cache[eccWords] = res
        return res
    }

    /**
     * RS parity of [data] (0..255) with [eccWords] check words (index.ts rsEcc L318-336): LFSR
     * remainder, four coefficients a word, shifted down one byte per data byte.
     */
    fun encode(data: IntArray, eccWords: Int): IntArray {
        val mul32 = cached(eccWords).mul32
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

    /** Evaluate a low-degree-first polynomial slice with Horner's method (decode.ts L984-988). */
    fun evalLow(poly: IntArray, offset: Int, length: Int, x: Int): Int {
        var value = 0
        for (i in length - 1 downTo 0) value = GF256.mul(value, x) xor poly[offset + i]
        return value
    }

    /**
     * Correct one deinterleaved RS block `block[offset until offset + length)` (data then [words]
     * ECC codewords) in place. Port of decode.ts L2369-2468.
     *
     * @param tmp scratch of at least [tmpSize] (`words`) entries (JS `tmp8` prefix)
     * @param rem scratch of at least 8 entries (JS `this.remainder`)
     * @return false when the block is uncorrectable (JS `FAIL.rs`); the block may then be
     *   partially modified, as in JS.
     */
    fun correct(block: IntArray, offset: Int, length: Int, words: Int, tmp: IntArray, rem: IntArray): Boolean {
        val exp = GF256.EXP
        val log = GF256.LOG
        // decode.ts L2342-2345: tmp8 slot layout
        val syndromes = 0
        val sigma = words
        val previous = 2 * words + 1
        val next = 3 * words + 2

        // decode.ts L2377-2393: LFSR remainder four coefficients a word; zero means intact.
        val products = cached(words).mul32
        val stride = (words + 3) shr 2
        val last = stride - 1
        rem.fill(0, 0, stride)
        for (i in 0 until length) {
            val base = (block[offset + i] xor (rem[0] and 0xff)) * stride
            for (j in 0 until last)
                rem[j] = ((rem[j] ushr 8) or (rem[j + 1] shl 24)) xor products[base + j]
            rem[last] = (rem[last] ushr 8) xor products[base + last]
        }
        var dirty = 0
        for (j in 0 until stride) dirty = dirty or rem[j]
        if (dirty == 0) return true

        // decode.ts L2397-2402: syndromes S_i = rem(alpha^i) * alpha^(-i*words)
        for (k in 0 until words) tmp[next + k] = (rem[k shr 2] shr ((k and 3) * 8)) and 0xff
        for (i in 0 until words) {
            var value = 0
            for (k in 0 until words) value = GF256.mul(value, exp[i]) xor tmp[next + k]
            tmp[syndromes + i] = GF256.mul(value, exp[255 - ((i * words) % 255)])
        }

        // decode.ts L2403-2441: Berlekamp-Massey
        tmp.fill(0, sigma, sigma + words + 1)
        tmp.fill(0, previous, previous + words + 1)
        tmp[sigma] = 1
        tmp[previous] = 1
        var sigmaLength = 1
        var previousLength = 1
        var degree = 0
        var shift = 1
        var discrepancy = 1
        for (n in 0 until words) {
            var delta = tmp[syndromes + n]
            for (i in 1..degree) delta = delta xor GF256.mul(tmp[sigma + i], tmp[syndromes + n - i])
            if (delta == 0) {
                shift++
                continue
            }
            val coefficient = GF256.mul(delta, GF256.inv(discrepancy))
            val nextLength = maxOf(sigmaLength, previousLength + shift)
            for (i in 0 until nextLength)
                tmp[next + i] = (if (i < sigmaLength) tmp[sigma + i] else 0) xor
                    (if (i >= shift && i - shift < previousLength)
                        GF256.mul(coefficient, tmp[previous + i - shift])
                    else 0)
            if (2 * degree <= n) {
                System.arraycopy(tmp, sigma, tmp, previous, sigmaLength)
                previousLength = sigmaLength
                degree = n + 1 - degree
                discrepancy = delta
                shift = 1
            } else shift++
            System.arraycopy(tmp, next, tmp, sigma, nextLength)
            sigmaLength = nextLength
        }
        while (sigmaLength > 1 && tmp[sigma + sigmaLength - 1] == 0) sigmaLength--
        val errors = sigmaLength - 1
        if (errors == 0 || 2 * errors > words) return false

        // decode.ts L2444-2448: error evaluator omega = sigma * S mod x^words (reuses previous)
        val omega = previous
        tmp.fill(0, omega, omega + words)
        for (i in 0 until sigmaLength) {
            var j = 0
            while (i + j < words) {
                tmp[omega + i + j] = tmp[omega + i + j] xor GF256.mul(tmp[sigma + i], tmp[syndromes + j])
                j++
            }
        }

        // decode.ts L2449-2453: Chien search (locations reuse next)
        val locations = next
        var locationCount = 0
        var x = 1
        while (x < 256 && locationCount < errors) {
            if (evalLow(tmp, sigma, sigmaLength, x) == 0) tmp[locations + locationCount++] = GF256.inv(x)
            x++
        }
        if (locationCount != errors) return false

        // decode.ts L2454-2466: Forney, applied in place
        for (i in 0 until locationCount) {
            val location = tmp[locations + i]
            val blockPos = length - 1 - log[location]
            if (blockPos < 0) return false
            val inverse = GF256.inv(location)
            var denominator = 1
            for (j in 0 until locationCount)
                if (i != j) denominator = GF256.mul(denominator, 1 xor GF256.mul(tmp[locations + j], inverse))
            block[offset + blockPos] = block[offset + blockPos] xor
                GF256.mul(evalLow(tmp, omega, words, inverse), GF256.inv(denominator))
        }
        return true
    }
}
