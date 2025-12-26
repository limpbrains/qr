package com.keklol

import kotlin.math.sqrt

/**
 * Represents a 2D point with x and y coordinates.
 */
data class Point(val x: Double, val y: Double) {
    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    operator fun unaryMinus() = Point(-x, -y)

    fun toInt() = Point(x.toInt().toDouble(), y.toInt().toDouble())
    fun mirror() = Point(y, x)

    companion object {
        fun distance(p1: Point, p2: Point): Double {
            val dx = p1.x - p2.x
            val dy = p1.y - p2.y
            return sqrt(dx * dx + dy * dy)
        }

        fun distance2(p1: Point, p2: Point): Double {
            val dx = p1.x - p2.x
            val dy = p1.y - p2.y
            return dx * dx + dy * dy
        }
    }
}

/**
 * Represents a detected QR code pattern (finder or alignment).
 */
data class Pattern(
    val x: Double,
    val y: Double,
    val moduleSize: Double,
    val count: Int
) {
    fun toPoint() = Point(x, y)

    fun equals(other: Pattern): Boolean {
        if (kotlin.math.abs(other.y - y) <= other.moduleSize &&
            kotlin.math.abs(other.x - x) <= other.moduleSize) {
            val diff = kotlin.math.abs(other.moduleSize - moduleSize)
            return diff <= 1.0 || diff <= moduleSize
        }
        return false
    }

    fun merge(other: Pattern): Pattern {
        val totalCount = count + other.count
        return Pattern(
            x = (count * x + other.count * other.x) / totalCount,
            y = (count * y + other.count * other.y) / totalCount,
            moduleSize = (count * moduleSize + other.count * other.moduleSize) / totalCount,
            count = totalCount
        )
    }
}

/**
 * Error correction levels for QR codes.
 * - LOW: ~7% error correction
 * - MEDIUM: ~15% error correction
 * - QUARTILE: ~25% error correction
 * - HIGH: ~30% error correction
 */
enum class ErrorCorrection(val code: Int) {
    LOW(0b01),
    MEDIUM(0b00),
    QUARTILE(0b11),
    HIGH(0b10);

    companion object {
        fun fromCode(code: Int): ErrorCorrection = entries.first { it.code == code }
    }
}

/**
 * QR code data encoding types.
 */
enum class EncodingType(val modeBits: String) {
    NUMERIC("0001"),
    ALPHANUMERIC("0010"),
    BYTE("0100"),
    KANJI("1000"),
    ECI("0111");

    companion object {
        private val bitsToType = entries.associateBy { it.modeBits }

        fun fromModeBits(bits: String): EncodingType? = bitsToType[bits]
    }
}

/**
 * QR code mask patterns (0-7).
 */
typealias Mask = Int

/**
 * Exception thrown when QR code decoding fails.
 */
open class QRDecodingException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ImageTooSmallException(message: String) : QRDecodingException(message)
class FinderNotFoundException(message: String) : QRDecodingException(message)
class InvalidFormatException(message: String) : QRDecodingException(message)
class InvalidVersionException(message: String) : QRDecodingException(message)
