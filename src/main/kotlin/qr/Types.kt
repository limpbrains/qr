package qr

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
 * Exception thrown when QR code decoding fails.
 */
open class QRDecodingException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Deprecated("no longer thrown")
class ImageTooSmallException(message: String) : QRDecodingException(message)
class FinderNotFoundException(message: String) : QRDecodingException(message)
class InvalidFormatException(message: String) : QRDecodingException(message)
class InvalidVersionException(message: String) : QRDecodingException(message)

/**
 * Terminal failure of a scan stage. Tags match upstream `FAIL` (decode.ts L193-202).
 */
enum class Fail(val tag: String) {
    DATA("data"),
    DIMENSION("dimension"),
    FINDER("finder"),
    FORMAT("format"),
    ALIGNMENT("alignment"),
    RS("rs"),
    TIMING("timing"),
    VERSION("version")
}

/**
 * Pixel layout of raw image data: 1 (GRAY), 3 (RGB) or 4 (RGBA) bytes per pixel.
 */
enum class PixelFormat { GRAY, RGB, RGBA }
