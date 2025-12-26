package com.keklol

/**
 * Represents an image with raw pixel data.
 *
 * @property width Image width in pixels
 * @property height Image height in pixels
 * @property data Raw pixel data in RGB or RGBA format
 */
data class Image(
    val width: Int,
    val height: Int,
    val data: ByteArray
) {
    init {
        require(width > 0) { "Width must be positive" }
        require(height > 0) { "Height must be positive" }
        require(data.isNotEmpty()) { "Data must not be empty" }
    }

    /**
     * Returns the number of bytes per pixel (3 for RGB, 4 for RGBA).
     */
    val bytesPerPixel: Int
        get() {
            val perPixel = data.size / (width * height)
            require(perPixel == 3 || perPixel == 4) {
                "Unknown image format, bytes per pixel=$perPixel"
            }
            return perPixel
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Image) return false
        return width == other.width && height == other.height && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + data.contentHashCode()
        return result
    }
}
