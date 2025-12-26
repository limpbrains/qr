package com.keklol

import kotlin.math.max
import kotlin.math.min

/**
 * Perspective transformation utilities for QR code detection.
 */
object Transform {

    /**
     * Compute transformation matrix from square to quadrilateral.
     */
    private fun squareToQuadrilateral(p: List<Point>): Array<DoubleArray> {
        val d3 = Point(
            p[0].x - p[1].x + p[2].x - p[3].x,
            p[0].y - p[1].y + p[2].y - p[3].y
        )

        return if (d3.x == 0.0 && d3.y == 0.0) {
            arrayOf(
                doubleArrayOf(p[1].x - p[0].x, p[2].x - p[1].x, p[0].x),
                doubleArrayOf(p[1].y - p[0].y, p[2].y - p[1].y, p[0].y),
                doubleArrayOf(0.0, 0.0, 1.0)
            )
        } else {
            val d1 = Point(p[1].x - p[2].x, p[1].y - p[2].y)
            val d2 = Point(p[3].x - p[2].x, p[3].y - p[2].y)
            val den = d1.x * d2.y - d2.x * d1.y
            val p13 = (d3.x * d2.y - d2.x * d3.y) / den
            val p23 = (d1.x * d3.y - d3.x * d1.y) / den
            arrayOf(
                doubleArrayOf(p[1].x - p[0].x + p13 * p[1].x, p[3].x - p[0].x + p23 * p[3].x, p[0].x),
                doubleArrayOf(p[1].y - p[0].y + p13 * p[1].y, p[3].y - p[0].y + p23 * p[3].y, p[0].y),
                doubleArrayOf(p13, p23, 1.0)
            )
        }
    }

    /**
     * Transform a quadrilateral region of the bitmap to a square.
     */
    fun transform(b: Bitmap, size: Int, from: List<Point>, to: List<Point>): Bitmap {
        val p = squareToQuadrilateral(to)

        // Calculate inverse of p for quadrilateral-to-square mapping
        val qToS = arrayOf(
            doubleArrayOf(
                p[1][1] * p[2][2] - p[2][1] * p[1][2],
                p[2][1] * p[0][2] - p[0][1] * p[2][2],
                p[0][1] * p[1][2] - p[1][1] * p[0][2]
            ),
            doubleArrayOf(
                p[2][0] * p[1][2] - p[1][0] * p[2][2],
                p[0][0] * p[2][2] - p[2][0] * p[0][2],
                p[1][0] * p[0][2] - p[0][0] * p[1][2]
            ),
            doubleArrayOf(
                p[1][0] * p[2][1] - p[2][0] * p[1][1],
                p[2][0] * p[0][1] - p[0][0] * p[2][1],
                p[0][0] * p[1][1] - p[1][0] * p[0][1]
            )
        )

        val sToQ = squareToQuadrilateral(from)

        // Combine transformations
        val transform = Array(3) { row ->
            DoubleArray(3) { col ->
                sToQ[row].mapIndexed { j, v -> v * qToS[j][col] }.sum()
            }
        }

        val res = Bitmap(size, size)
        val points = DoubleArray(2 * size)

        for (y in 0 until size) {
            for (i in 0 until 2 * size - 1 step 2) {
                val x = i / 2 + 0.5
                val y2 = y + 0.5
                val den = transform[2][0] * x + transform[2][1] * y2 + transform[2][2]
                points[i] = ((transform[0][0] * x + transform[0][1] * y2 + transform[0][2]) / den).toInt().toDouble()
                points[i + 1] = ((transform[1][0] * x + transform[1][1] * y2 + transform[1][2]) / den).toInt().toDouble()
            }

            for (i in 0 until 2 * size step 2) {
                val px = max(0.0, min(points[i], (b.width - 1).toDouble())).toInt()
                val py = max(0.0, min(points[i + 1], (b.height - 1).toDouble())).toInt()
                if (b.get(px, py) == true) {
                    res.set(i / 2, y, true)
                }
            }
        }

        return res
    }
}
