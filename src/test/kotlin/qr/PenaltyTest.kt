package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Ports of qr.test.ts 'Penalty' 1-3 (L390-460) and the scalar-reference random grids (L462-536). */
class PenaltyTest {
    private fun checkSums(ver: Int, ecc: ErrorCorrection, data: String, vectors: List<List<Int>>) {
        val words = JsEncoderGoldens.ints(data)
        for (i in 0 until 8) {
            val m = Symbol.draw(ver, ecc, words, i, test = true)
            assertEquals(vectors[i].sum(), Penalty.score(m), "v$ver $ecc mask $i")
        }
    }

    @Test
    fun `python-qrcode penalty 1, v1-L`() = checkSums(
        1, ErrorCorrection.LOW, JsEncoderGoldens.PENALTY1_DATA,
        // qr.test.ts L392-401
        listOf(
            listOf(179, 141, 120, 0),
            listOf(204, 195, 240, 0),
            listOf(239, 186, 200, 0),
            listOf(205, 207, 200, 0),
            listOf(210, 192, 240, 0),
            listOf(225, 192, 120, 0),
            listOf(230, 210, 160, 0),
            listOf(207, 189, 160, 0),
        ),
    )

    @Test
    fun `python-qrcode penalty 2, v3-M`() = checkSums(
        3, ErrorCorrection.MEDIUM, JsEncoderGoldens.PENALTY2_DATA,
        // qr.test.ts L415-424
        listOf(
            listOf(289, 312, 80, 0),
            listOf(296, 282, 80, 0),
            listOf(317, 375, 160, 0),
            listOf(260, 282, 120, 0),
            listOf(305, 330, 40, 0),
            listOf(316, 372, 200, 0),
            listOf(321, 339, 200, 0),
            listOf(334, 360, 200, 0),
        ),
    )

    @Test
    fun `python-qrcode penalty 3, v2-L`() = checkSums(
        2, ErrorCorrection.LOW, JsEncoderGoldens.PENALTY3_DATA,
        // qr.test.ts L440-449
        listOf(
            listOf(251, 261, 240, 0),
            listOf(232, 255, 240, 0),
            listOf(297, 330, 120, 0),
            listOf(268, 249, 200, 0),
            listOf(244, 279, 200, 0),
            listOf(263, 267, 160, 0),
            listOf(270, 258, 200, 0),
            listOf(261, 252, 240, 0),
        ),
    )

    // qr.test.ts L467-519: direct scalar transcription of ISO/IEC 18004 N1..N4.
    private fun scalarPenalty(g: Array<BooleanArray>): Int {
        val n = g.size
        var adj = 0
        fun runs(get: (Int, Int) -> Boolean) {
            for (i in 0 until n) {
                var run = 1
                for (j in 1 until n) {
                    if (get(i, j) == get(i, j - 1)) run++
                    else {
                        if (run >= 5) adj += 3 + run - 5
                        run = 1
                    }
                }
                if (run >= 5) adj += 3 + run - 5
            }
        }
        runs { i, j -> g[i][j] }
        runs { i, j -> g[j][i] }
        var boxes = 0
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            val v = g[y][x]
            if (g[y][x + 1] == v && g[y + 1][x] == v && g[y + 1][x + 1] == v) boxes++
        }
        var finder = 0
        val pats = arrayOf(
            intArrayOf(1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0),
            intArrayOf(0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1),
        )
        fun scan(get: (Int, Int) -> Boolean) {
            for (i in 0 until n) {
                var j = 0
                while (j + 11 <= n) {
                    for (p in pats) {
                        var ok = true
                        for (k in 0 until 11) {
                            if (get(i, j + k) != (p[k] != 0)) {
                                ok = false
                                break
                            }
                        }
                        if (ok) finder++
                    }
                    j++
                }
            }
        }
        scan { i, j -> g[i][j] }
        scan { i, j -> g[j][i] }
        var dark = 0
        for (row in g) for (c in row) if (c) dark++
        val total = n * n
        val steps = Math.ceil(maxOf(0, Math.abs(dark * 100 - total * 50) - total * 5).toDouble() / (total * 5)).toInt()
        return adj + 3 * boxes + 40 * finder + 10 * steps
    }

    @Test
    fun `penalty matches scalar spec reference on random grids`() {
        // qr.test.ts L520-535: Lehmer RNG, dark ratio swept across the N4 bands.
        var seed = 0x5eedL
        fun rnd(): Long {
            seed = (seed * 48271) % 0x7fffffff
            return seed
        }
        for (size in intArrayOf(5, 8, 21, 31, 32, 33, 34, 40, 64, 65)) {
            for (iter in 0 until 10) {
                val p = 0.2 + 0.6 * ((iter % 5) / 4.0)
                val g = Array(size) { BooleanArray(size) { rnd() / 2147483647.0 < p } }
                assertEquals(scalarPenalty(g), Penalty.score(g), "size=$size iter=$iter")
            }
        }
    }
}
