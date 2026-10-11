package qr

/**
 * Mask penalty of a test-form symbol (index.ts runsPenaltyVertical/finderPenaltyVertical/penaltyScore
 * L538-650), over rows and columns: N1 3+(L-5) per run >= 5, N2 3 per 2x2 same-colour box, N3 40 per
 * `10111010000` / `00001011101` window, N4 10 per 5% step of the dark ratio away from 45..55%.
 * The JS `limit` early exit only skips work for masks that cannot win, so it is not ported.
 */
internal object Penalty {
    fun score(m: Array<BooleanArray>): Int {
        val size = m.size
        val t = Array(size) { y -> BooleanArray(size) { x -> m[x][y] } }
        var score = 0
        // N1 (L538-564)
        for (g in arrayOf(m, t)) for (row in g) {
            var run = 1
            for (x in 1..size) {
                if (x < size && row[x] == row[x - 1]) run++
                else {
                    if (run >= 5) score += run - 2
                    run = 1
                }
            }
        }
        // N2 + N4 (L620-645)
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
        val num = maxOf(0, Math.abs(dark * 100 - total * 50) - total * 5)
        val den = total * 5
        val darkSteps = (num + den - 1) / den // Math.ceil(num / den)
        score += 3 * boxes + 10 * darkSteps
        // N3 (L566-603)
        val p1 = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val p2 = p1.reversedArray()
        var finders = 0
        for (g in arrayOf(m, t)) for (row in g) for (x in 0..size - 11) {
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
}
