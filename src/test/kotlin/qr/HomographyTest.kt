package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * [Homography] against V8: hypot/cbrt fixtures from `node tools/js-dump/math-fixtures.mjs
 * src/test/resources` (JS shortest round-trip strings, parsed exactly by toDouble()).
 */
class HomographyTest {
    private fun lines(name: String) =
        HomographyTest::class.java.getResource("/$name")!!.readText().lines().filter { it.isNotBlank() }

    @Test
    fun hypotMatchesV8() {
        val rows = lines("hypot-fixture.txt")
        assertEquals(500, rows.size)
        var mismatches = 0
        for (row in rows) {
            val (dx, dy, expected) = row.split(' ').map { it.toDouble() }
            if (Homography.hypot(dx, dy) != expected) mismatches++
            assertEquals(expected, Homography.distance(0.0, 0.0, dx, dy), row)
        }
        assertEquals(0, mismatches)
    }

    @Test
    fun hypotSpecialValues() {
        assertEquals(Double.POSITIVE_INFINITY, Homography.hypot(Double.NaN, Double.NEGATIVE_INFINITY))
        assertEquals(Double.NaN, Homography.hypot(Double.NaN, 1.0))
        assertEquals(0.0, Homography.hypot(-0.0, 0.0))
    }

    @Test
    fun cbrtMatchesV8() {
        val rows = lines("cbrt-fixture.txt")
        assertEquals(209, rows.size)
        for (row in rows) {
            val (x, expected) = row.split(' ').map { it.toDouble() }
            assertEquals(expected, Homography.cbrt(x), row)
        }
    }

    @Test
    fun snapSizeFollowsJsRoundAndInt32() {
        assertEquals(21, Homography.snapSize(21.0))
        assertEquals(25, Homography.snapSize(23.0)) // (23-17)/4 = 1.5 -> 2 (ties toward +Inf)
        assertEquals(13, Homography.snapSize(14.0)) // -0.75 -> -1
        assertEquals(17, Homography.snapSize(15.0)) // -0.5 -> -0 -> 17
        assertEquals(0, Homography.snapSize(1e300)) // ToInt32 wraps modulo 2^32 (values from node)
        assertEquals(1, Homography.snapSize(17179869184.0))
        assertEquals(0, Homography.snapSize(Double.NaN))
        assertEquals(0, Homography.snapSize(Double.POSITIVE_INFINITY))
        assertEquals(0, Homography.snapSize(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun squareToQuadRoundTrip() {
        val quads = listOf(
            doubleArrayOf(10.0, 20.0, 110.0, 25.0, 105.0, 130.0, 5.0, 118.0), // perspective
            doubleArrayOf(0.0, 0.0, 50.0, 0.0, 50.0, 50.0, 0.0, 50.0), // affine fast path
        )
        val m = DoubleArray(9)
        val corners = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val out = DoubleArray(2)
        for (q in quads) {
            Homography.squareToQuad(m, q)
            for (k in 0 until 4) {
                Homography.map(m, corners[2 * k], corners[2 * k + 1], out)
                assertEquals(q[2 * k], out[0], 1e-9)
                assertEquals(q[2 * k + 1], out[1], 1e-9)
                assertEquals(out[0], Homography.mapX(m, corners[2 * k], corners[2 * k + 1]))
                assertEquals(out[1], Homography.mapY(m, corners[2 * k], corners[2 * k + 1]))
            }
            // adj(M) * M = det(M) * I, and ptMul may write into its left operand.
            val adj = DoubleArray(9)
            Homography.adjugate(adj, m)
            val p = adj.copyOf()
            Homography.ptMul(p, p, m)
            val det = p[0]
            for (r in 0 until 3) for (c in 0 until 3) {
                val want = if (r == c) det else 0.0
                assertEquals(want, p[r * 3 + c], abs(det) * 1e-12)
            }
        }
    }

    @Test
    fun packQuadAndDist2() {
        val q = DoubleArray(8)
        Homography.packQuad(q, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)
        assertEquals((1..8).map { it.toDouble() }, q.toList())
        val pts = doubleArrayOf(1.0, 2.0, 0.0, 0.0, 4.0, 6.0, 0.0, 0.0)
        assertEquals(25.0, Homography.dist2(pts, 0, 4))
    }
}
