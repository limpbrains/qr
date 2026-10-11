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
 * Raw input pixel layout (JS `DecodeFormat` / `FORMATS`, decode.ts L499-519): [step] bytes per
 * pixel and [bits] per luma sample. Planar formats (step <= 2) read only the leading luma plane
 * (10/12-bit samples are little-endian and shifted down to 8 bits); packed four-byte formats
 * ignore the fourth byte, and `(r + 2g + b) >> 2` is symmetric in r/b so BGR(A/X) == RGB(A/X).
 */
enum class PixelFormat(val step: Int, val bits: Int) {
    RGB(3, 8),
    RGBA(4, 8),
    RGBX(4, 8),
    BGRA(4, 8),
    BGRX(4, 8),
    I420(1, 8),
    I420A(1, 8),
    I422(1, 8),
    I444(1, 8),
    NV12(1, 8),
    I420P10(2, 10),
    I420P12(2, 12);

    companion object {
        /** Kotlin alias: tight 8-bit luma plane (= [I420]). */
        @JvmField
        val GRAY: PixelFormat = I420
    }
}

/**
 * Decoder options (JS `DecodeOpts`, decode.ts L61-97; validated as in validateOpts L520-551).
 *
 * @param format input pixel format; null infers RGB/RGBA from an exact `3*px` / `4*px` length
 * @param effort retry budget, >= 1 (`Int.MAX_VALUE` = JS Infinity)
 * @param timeLimitMs retry wall-time budget in ms, finite >= 0 or `Double.POSITIVE_INFINITY`
 * @param nativeLimit native-layer search gate, >= 0
 * @param textDecoder optional byte-segment decoder `(bytes, eci) -> text`
 * @param pointsOnDetect fired once when a search succeeds or terminally fails, with finder points
 *   in input-image coordinates and the decoded text or failure
 * @param imageOnResult fired with the final sampled module grid (1px per module, RGBA) on success
 * @param imageOnBitmap debug: fired with each binarized plane as an RGBA image before detection
 */
data class DecodeOptions(
    val format: PixelFormat? = null,
    val effort: Int = 1,
    val timeLimitMs: Double = 1000.0 / 60,
    val nativeLimit: Int = Int.MAX_VALUE,
    val textDecoder: ((ByteArray, Int) -> String)? = null,
    val pointsOnDetect: ((FinderPoints, DecodeResult) -> Unit)? = null,
    val imageOnResult: ((Image) -> Unit)? = null,
    val imageOnBitmap: ((Image) -> Unit)? = null,
)

/** Result of a batch decode: every decoded text plus the terminal failure or error, if any. */
data class BatchResult(val texts: List<String>, val failure: Fail?, val error: Exception? = null)
