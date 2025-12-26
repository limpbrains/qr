package qr

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A 2D bitmap for storing black/white QR code data.
 *
 * Internal storage uses a 2D array where:
 * - null = not yet written
 * - true = foreground (black/dark)
 * - false = background (white/light)
 */
class Bitmap private constructor(
    val width: Int,
    val height: Int,
    val data: Array<Array<Boolean?>>
) {
    constructor(size: Int) : this(size, size)

    constructor(width: Int, height: Int) : this(
        width,
        height,
        Array(height) { arrayOfNulls<Boolean>(width) }
    )

    /**
     * Get the value at point p.
     */
    fun point(p: Point): Boolean? = data[p.y.toInt()][p.x.toInt()]

    /**
     * Get the value at coordinates (x, y).
     */
    fun get(x: Int, y: Int): Boolean? = data[y][x]

    /**
     * Set the value at coordinates (x, y).
     */
    fun set(x: Int, y: Int, value: Boolean?) {
        data[y][x] = value
    }

    /**
     * Check if point p is inside the bitmap.
     */
    fun isInside(p: Point): Boolean =
        p.x >= 0 && p.x < width && p.y >= 0 && p.y < height

    /**
     * Fill a rectangular region with a value.
     */
    fun rect(x: Int, y: Int, w: Int, h: Int, value: Boolean?): Bitmap {
        val actualW = min(w, width - x)
        val actualH = min(h, height - y)
        for (yy in 0 until actualH) {
            for (xx in 0 until actualW) {
                data[y + yy][x + xx] = value
            }
        }
        return this
    }

    /**
     * Fill a rectangular region using a function.
     */
    fun rect(x: Int, y: Int, w: Int, h: Int, fn: (xx: Int, yy: Int, current: Boolean?) -> Boolean?): Bitmap {
        val actualW = min(w, width - x)
        val actualH = min(h, height - y)
        for (yy in 0 until actualH) {
            for (xx in 0 until actualW) {
                data[y + yy][x + xx] = fn(xx, yy, data[y + yy][x + xx])
            }
        }
        return this
    }

    /**
     * Draw a horizontal line.
     */
    fun hLine(x: Int, y: Int, len: Int, value: Boolean?): Bitmap = rect(x, y, len, 1, value)

    /**
     * Draw a vertical line.
     */
    fun vLine(x: Int, y: Int, len: Int, value: Boolean?): Bitmap = rect(x, y, 1, len, value)

    /**
     * Draw a horizontal line using a function.
     */
    fun hLine(x: Int, y: Int, len: Int, fn: (xx: Int, current: Boolean?) -> Boolean?): Bitmap {
        val actualLen = min(len, width - x)
        for (xx in 0 until actualLen) {
            data[y][x + xx] = fn(xx, data[y][x + xx])
        }
        return this
    }

    /**
     * Draw a vertical line using a function.
     */
    fun vLine(x: Int, y: Int, len: Int, fn: (yy: Int, current: Boolean?) -> Boolean?): Bitmap {
        val actualLen = min(len, height - y)
        for (yy in 0 until actualLen) {
            data[y + yy][x] = fn(yy, data[y + yy][x])
        }
        return this
    }

    /**
     * Add a border around the bitmap.
     */
    fun border(borderSize: Int, value: Boolean?): Bitmap {
        val newWidth = width + 2 * borderSize
        val newHeight = height + 2 * borderSize
        val result = Bitmap(newWidth, newHeight)

        // Fill with border value
        for (y in 0 until newHeight) {
            for (x in 0 until newWidth) {
                result.data[y][x] = value
            }
        }

        // Copy original data
        for (y in 0 until height) {
            for (x in 0 until width) {
                result.data[y + borderSize][x + borderSize] = data[y][x]
            }
        }

        return result
    }

    /**
     * Embed another bitmap at the given position.
     */
    fun embed(x: Int, y: Int, other: Bitmap): Bitmap {
        val actualX = mod(x, width)
        val actualY = mod(y, height)
        for (yy in 0 until other.height) {
            val targetY = actualY + yy
            if (targetY >= height) break
            for (xx in 0 until other.width) {
                val targetX = actualX + xx
                if (targetX >= width) break
                data[targetY][targetX] = other.data[yy][xx]
            }
        }
        return this
    }

    /**
     * Extract a rectangular slice of the bitmap.
     */
    fun slice(x: Int, y: Int, w: Int, h: Int): Bitmap {
        val result = Bitmap(w, h)
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                val srcX = x + xx
                val srcY = y + yy
                if (srcX in 0 until width && srcY in 0 until height) {
                    result.data[yy][xx] = data[srcY][srcX]
                }
            }
        }
        return result
    }

    /**
     * Clone this bitmap.
     */
    fun clone(): Bitmap {
        val result = Bitmap(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                result.data[y][x] = data[y][x]
            }
        }
        return result
    }

    /**
     * Convert to ASCII art representation.
     */
    fun toASCII(): String {
        val sb = StringBuilder()
        // Terminal character height is 2x character width, so we process two rows
        var y = 0
        while (y < height) {
            for (x in 0 until width) {
                val first = data[y][x] ?: false
                val second = if (y + 1 >= height) true else (data[y + 1][x] ?: false)
                sb.append(
                    when {
                        !first && !second -> '\u2588' // both white
                        !first && second -> '\u2580'  // top white
                        first && !second -> '\u2584'  // bottom white
                        else -> ' '                    // both black
                    }
                )
            }
            sb.append('\n')
            y += 2
        }
        return sb.toString()
    }

    /**
     * Convert to string representation for debugging.
     */
    override fun toString(): String {
        return data.joinToString("\n") { row ->
            row.joinToString("") { cell ->
                when (cell) {
                    null -> "?"
                    true -> "X"
                    false -> " "
                }
            }
        }
    }

    /**
     * Convert bitmap to Image (RGBA format).
     */
    fun toImage(isRGB: Boolean = false): Image {
        val bytesPerPixel = if (isRGB) 3 else 4
        val imageData = ByteArray(height * width * bytesPerPixel)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if (data[y][x] == true) 0 else 255
                imageData[i++] = value.toByte()
                imageData[i++] = value.toByte()
                imageData[i++] = value.toByte()
                if (!isRGB) {
                    imageData[i++] = 255.toByte() // alpha
                }
            }
        }
        return Image(width, height, imageData)
    }

    companion object {
        private fun mod(a: Int, b: Int): Int {
            val result = a % b
            return if (result >= 0) result else b + result
        }

        /**
         * Create a bitmap from a string representation.
         * 'X' = true (black), ' ' = false (white), '?' = null
         */
        fun fromString(s: String): Bitmap {
            val lines = s.trim().split('\n')
            val height = lines.size
            val width = lines.maxOfOrNull { it.length } ?: 0
            val result = Bitmap(width, height)
            for ((y, line) in lines.withIndex()) {
                for ((x, char) in line.withIndex()) {
                    result.data[y][x] = when (char) {
                        'X' -> true
                        ' ' -> false
                        '?' -> null
                        else -> throw IllegalArgumentException("Unknown character: $char")
                    }
                }
            }
            return result
        }
    }
}
