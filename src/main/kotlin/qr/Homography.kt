package qr

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Projective-map and distance helpers from qr v0.7.2 decode.ts. Maps are row-major
 * `DoubleArray(9)` `[a11 a12 a13 a21 a22 a23 a31 a32 a33]` applied to column vectors `[u, v, 1]`
 * with a perspective divide. Every expression keeps the JS operand order (bit-exact doubles).
 */
internal object Homography {
    // decode.ts L372-373: squared center distance of two stride-4 pattern records (element offsets).
    fun dist2(pts: DoubleArray, a: Int, b: Int): Double {
        val dx = pts[a] - pts[b]
        val dy = pts[a + 1] - pts[b + 1]
        // JS `** 2` on doubles is exactly x * x (fdlibm pow special-cases y == 2).
        return dx * dx + dy * dy
    }

    // decode.ts L374-375: Math.hypot(second.x - first.x, second.y - first.y)
    fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double = hypot(x2 - x1, y2 - y1)

    /**
     * V8's Math.hypot (builtins math.tq MathHypot) for two arguments: infinity wins over NaN,
     * then max-scaled Kahan-compensated sum of squares. Not `sqrt(a*a + b*b)` and not
     * java.lang.Math.hypot: both differ from V8 in the last ulp.
     */
    fun hypot(a: Double, b: Double): Double {
        val absA = abs(a)
        val absB = abs(b)
        val aNaN = a.isNaN()
        val bNaN = b.isNaN()
        var max = 0.0
        if (!aNaN && absA > max) max = absA
        if (!bNaN && absB > max) max = absB
        if (max == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
        if (aNaN || bNaN) return Double.NaN
        if (max == 0.0) return 0.0
        var sum = 0.0
        var compensation = 0.0
        var n = absA / max
        var summand = n * n - compensation
        var preliminary = sum + summand
        compensation = (preliminary - sum) - summand
        sum = preliminary
        n = absB / max
        summand = n * n - compensation
        preliminary = sum + summand
        compensation = (preliminary - sum) - summand
        sum = preliminary
        return sqrt(sum) * max
    }

    /**
     * JS Math.cbrt = V8 `base::ieee754::cbrt`, the FreeBSD msun s_cbrt.c (2009 rewrite).
     * java.lang.StrictMath.cbrt is the older fdlibm 5.3 algorithm and differs by 1 ulp on ~8%
     * of inputs, so the V8 routine is ported here.
     */
    fun cbrt(x: Double): Double {
        val bits = java.lang.Double.doubleToRawLongBits(x)
        var hx = (bits ushr 32).toInt()
        val low = bits.toInt()
        val sign = hx and 0x80000000.toInt()
        hx = hx xor sign
        if (hx >= 0x7ff00000) return x + x // cbrt(NaN, Inf) is itself
        var t: Double
        if (hx < 0x00100000) { // zero or subnormal
            if ((hx or low) == 0) return x // cbrt(+-0) is itself
            t = 18014398509481984.0 * x // 2^54 * x
            val high = (java.lang.Double.doubleToRawLongBits(t) ushr 32).toInt()
            t = fromHigh(sign or ((high and 0x7fffffff) / 3 + CBRT_B2))
        } else {
            t = fromHigh(sign or (hx / 3 + CBRT_B1))
        }
        // New cbrt to 23 bits: cbrt(x) = t*cbrt(x/t**3) ~= t*P(t**3/x)
        var r = (t * t) * (t / x)
        t = t * ((CBRT_P0 + r * (CBRT_P1 + r * CBRT_P2)) + ((r * r) * r) * (CBRT_P3 + r * CBRT_P4))
        // Round t away from zero to 23 bits.
        t = java.lang.Double.longBitsToDouble(
            (java.lang.Double.doubleToRawLongBits(t) + 0x80000000L) and -0x40000000L
        )
        // One Newton step to 53 bits.
        val s = t * t
        r = x / s
        val w = t + t
        r = (r - t) / (w + r)
        t = t + t * r
        return t
    }

    private fun fromHigh(high: Int): Double = java.lang.Double.longBitsToDouble(high.toLong() shl 32)

    private const val CBRT_B1 = 715094163 // (1023-1023/3-0.03306235651)*2**20
    private const val CBRT_B2 = 696219795 // (1023-1023/3-54/3-0.03306235651)*2**20
    private const val CBRT_P0 = 1.87595182427177009643
    private const val CBRT_P1 = -1.88497979543377169875
    private const val CBRT_P2 = 1.621429720105354466140
    private const val CBRT_P3 = -0.758397934778766047437
    private const val CBRT_P4 = 0.145996192886612446982

    // decode.ts L398-437: unit square -> quad (`points` = x1,y1 .. x4,y4).
    fun squareToQuad(out: DoubleArray, points: DoubleArray) {
        val x1 = points[0]
        val y1 = points[1]
        val x2 = points[2]
        val y2 = points[3]
        val x3 = points[4]
        val y3 = points[5]
        val x4 = points[6]
        val y4 = points[7]
        val dx3 = x1 - x2 + x3 - x4
        val dy3 = y1 - y2 + y3 - y4
        if (dx3 == 0.0 && dy3 == 0.0) {
            out[0] = x2 - x1
            out[1] = x3 - x2
            out[2] = x1
            out[3] = y2 - y1
            out[4] = y3 - y2
            out[5] = y1
            out[6] = 0.0
            out[7] = 0.0
            out[8] = 1.0
            return
        }
        val dx1 = x2 - x3
        val dx2 = x4 - x3
        val dy1 = y2 - y3
        val dy2 = y4 - y3
        val den = dx1 * dy2 - dx2 * dy1
        val a31 = (dx3 * dy2 - dx2 * dy3) / den
        val a32 = (dx1 * dy3 - dx3 * dy1) / den
        out[0] = x2 - x1 + a31 * x2
        out[1] = x4 - x1 + a32 * x4
        out[2] = x1
        out[3] = y2 - y1 + a31 * y2
        out[4] = y4 - y1 + a32 * y4
        out[5] = y1
        out[6] = a31
        out[7] = a32
        out[8] = 1.0
    }

    // decode.ts L440-450: o = adj(m); adj[r][c] is the (c, r) cofactor.
    fun adjugate(o: DoubleArray, m: DoubleArray) {
        for (i in 0 until 9) {
            val r = i / 3
            val c = i % 3
            val r1 = ((c + 1) % 3) * 3
            val r2 = ((c + 2) % 3) * 3
            val c1 = (r + 1) % 3
            val c2 = (r + 2) % 3
            o[i] = m[r1 + c1] * m[r2 + c2] - m[r1 + c2] * m[r2 + c1]
        }
    }

    // decode.ts L451-459: o = a * b; `o` may alias `a` (one input row is cached).
    fun ptMul(o: DoubleArray, a: DoubleArray, b: DoubleArray) {
        var r = 0
        while (r < 9) {
            val a0 = a[r]
            val a1 = a[r + 1]
            val a2 = a[r + 2]
            for (c in 0 until 3) o[r + c] = a0 * b[c] + a1 * b[c + 3] + a2 * b[c + 6]
            r += 3
        }
    }

    // decode.ts L698-717: clockwise quadrilateral as interleaved x/y.
    fun packQuad(
        quad: DoubleArray,
        x0: Double, y0: Double, x1: Double, y1: Double,
        x2: Double, y2: Double, x3: Double, y3: Double,
    ) {
        quad[0] = x0
        quad[1] = y0
        quad[2] = x1
        quad[3] = y1
        quad[4] = x2
        quad[5] = y2
        quad[6] = x3
        quad[7] = y3
    }

    // decode.ts L905-911 (mapPoint), x component. Same den expression as [mapY].
    fun mapX(map: DoubleArray, x: Double, y: Double): Double {
        val den = map[6] * x + map[7] * y + map[8]
        return (map[0] * x + map[1] * y + map[2]) / den
    }

    // decode.ts L905-911 (mapPoint), y component.
    fun mapY(map: DoubleArray, x: Double, y: Double): Double {
        val den = map[6] * x + map[7] * y + map[8]
        return (map[3] * x + map[4] * y + map[5]) / den
    }

    // decode.ts L905-911 (mapPoint) into out[0..1] without allocation.
    fun map(map: DoubleArray, x: Double, y: Double, out: DoubleArray) {
        val den = map[6] * x + map[7] * y + map[8]
        out[0] = (map[0] * x + map[1] * y + map[2]) / den
        out[1] = (map[3] * x + map[4] * y + map[5]) / den
    }

    // decode.ts L1273: (Math.round((estimate - 17) / 4) * 4 + 17) | 0. Non-finite -> 0.
    fun snapSize(estimate: Double): Int = toInt32(jsRound((estimate - 17) / 4) * 4 + 17)

    /** JS Math.round: nearest integer, ties toward +Infinity; NaN/Infinity pass through. */
    fun jsRound(x: Double): Double {
        if (!x.isFinite() || abs(x) >= 4503599627370496.0) return x // 2^52: already integral
        // java.lang.Math.round is floor(x + 0.5) without the double-rounding error, as in V8.
        return Math.round(x).toDouble()
    }

    /** JS ToInt32 (`x | 0`): NaN/Infinity -> 0, otherwise truncate and wrap modulo 2^32. */
    fun toInt32(x: Double): Int {
        if (!x.isFinite()) return 0
        val t = if (x < 0) kotlin.math.ceil(x) else kotlin.math.floor(x)
        return (t % 4294967296.0).toLong().toInt()
    }
}
