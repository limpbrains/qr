package qr

/**
 * One-shot QR decoding (qr v0.7.2 `decodeQR`, decode.ts L3135-3150) on a shared [QRScanner].
 *
 * Usage:
 * ```kotlin
 * val decoded = QRDecoder.decode(Image(width, height, data)) // gray, RGB or RGBA
 * val decoded = QRDecoder.decode(width, height, data)
 * ```
 *
 * The scanner (JS defaults: effort 1, timeLimit 1000/60 ms, nativeLimit Infinity) is created
 * lazily. Thread-safe; the scanner is rebuilt when the frame size changes, so a constant camera
 * stream allocates once. The arenas are wiped after every call as JS `decodeQR` does.
 */
object QRDecoder {
    @Volatile
    private var scanner: QRScanner? = null

    /**
     * Decode the first QR code in [image].
     *
     * @throws FinderNotFoundException when no finder triple was found
     * @throws InvalidFormatException when no format word was readable
     * @throws InvalidVersionException when the version words disagree with the sampled size
     * @throws QRDecodingException for every other failure (message = JS error tag)
     * @throws IllegalArgumentException for invalid image dimensions or data
     */
    fun decode(image: Image): String = synchronized(this) {
        var s = scanner
        if (s == null || image.width != s.maxWidth || image.height != s.maxHeight) {
            s = QRScanner(image.width, image.height)
            scanner = s
        }
        try {
            s.addImage(image)
            val result = s.decode()
            if (result.isNotEmpty()) return result[0]
            val fail = s.lastFailure ?: Fail.FINDER
            throw when (fail) {
                Fail.FINDER -> FinderNotFoundException(fail.tag)
                Fail.FORMAT -> InvalidFormatException(fail.tag)
                Fail.VERSION -> InvalidVersionException(fail.tag)
                else -> QRDecodingException(fail.tag)
            }
        } finally {
            // One-shot calls leave no source image data resident after return.
            s.clean()
        }
    }

    /**
     * Decode the first QR code in raw pixel data. Format is inferred from `data.size` (see
     * [Image.format]): exactly 3·px bytes ⇒ RGB, exactly 4·px ⇒ RGBA, otherwise ≥ px bytes ⇒ gray.
     */
    fun decode(width: Int, height: Int, data: ByteArray): String {
        return decode(Image(width, height, data))
    }
}
