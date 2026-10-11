package qr

/**
 * Represents an image with raw pixel data.
 *
 * @property width Image width in pixels
 * @property height Image height in pixels
 * @property data Raw pixel data in Grayscale, RGB, or RGBA format
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
     * Pixel layout inferred from the buffer length: exactly 3 or 4 bytes per pixel -> RGB / RGBA;
     * any other length of at least one byte per pixel -> GRAY (the JS planar rule accepts oversize
     * luma buffers, decode.ts L589-596).
     */
    val format: PixelFormat
        get() {
            val px = width * height
            return when {
                data.size == 3 * px -> PixelFormat.RGB
                data.size == 4 * px -> PixelFormat.RGBA
                data.size >= px -> PixelFormat.GRAY
                else -> throw IllegalArgumentException(
                    "Unknown image format: ${data.size} bytes for ${width}x$height"
                )
            }
        }

    /**
     * Returns the number of bytes per pixel (1 for Grayscale, 3 for RGB, 4 for RGBA), see [format].
     */
    val bytesPerPixel: Int
        get() = when (format) {
            PixelFormat.GRAY -> 1
            PixelFormat.RGB -> 3
            PixelFormat.RGBA -> 4
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
