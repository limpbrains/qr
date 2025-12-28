package qr

/**
 * QR Code decoder.
 *
 * Usage:
 * ```kotlin
 * // From Image object
 * val image = Image(width, height, rgbaByteArray)
 * val decoded = QRDecoder.decode(image)
 *
 * // From raw bytes
 * val decoded = QRDecoder.decode(width, height, rgbaByteArray)
 * ```
 */
object QRDecoder {

    // Threshold offsets to try when decoding fails
    // Different JPEG decoders (jpeg-js vs ImageIO) produce slightly different pixel values
    // Trying different thresholds helps compensate for this
    private val THRESHOLD_OFFSETS = listOf(0)

    /**
     * Decode a QR code from an Image.
     *
     * @param image The image containing the QR code (RGB or RGBA format)
     * @return The decoded string content
     * @throws QRDecodingException if decoding fails
     */
    fun decode(image: Image): String {
        // Validate input
        require(image.width > 0) { "Invalid image width: ${image.width}" }
        require(image.height > 0) { "Invalid image height: ${image.height}" }
        require(image.data.isNotEmpty()) { "Image data is empty" }

        var lastException: Exception? = null

        // Try different threshold offsets to handle JPEG decoder differences
        for (offset in THRESHOLD_OFFSETS) {
            try {
                return decodeWithOffset(image, offset)
            } catch (e: Exception) {
                lastException = e
                // Continue to next offset
            }
        }

        // All offsets failed, throw the last exception
        throw lastException ?: QRDecodingException("Failed to decode QR code")
    }

    /**
     * Internal decode method with a specific threshold offset.
     */
    private fun decodeWithOffset(image: Image, thresholdOffset: Int): String {
        // Convert image to binary bitmap with threshold offset
        val bitmap = PatternDetector.toBitmap(image, thresholdOffset)

        // Detect QR code patterns and extract aligned bits
        val (bits, _) = PatternDetector.detect(bitmap)

        // Decode the extracted bits
        return BitDecoder.decodeBitmap(bits)
    }

    /**
     * Decode a QR code from raw image data.
     *
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param data Raw pixel data in RGB or RGBA format
     * @return The decoded string content
     * @throws QRDecodingException if decoding fails
     */
    fun decode(width: Int, height: Int, data: ByteArray): String {
        return decode(Image(width, height, data))
    }
}
