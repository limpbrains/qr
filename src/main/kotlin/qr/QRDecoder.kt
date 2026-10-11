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
            throwFor(s.lastFailure ?: Fail.FINDER)
        } finally {
            // One-shot calls leave no source image data resident after return.
            s.clean()
        }
    }

    /**
     * decode.ts L3135-3149 (`decodeQR(img, opts)`): decode the first QR code in [image] with
     * [options] on a fresh scanner sized to the image. The pixel format is `options.format`, else
     * inferred by [Image.format].
     *
     * Options and callbacks are bound to a scanner at construction, so every call allocates its
     * own arenas (the cached scanner of `decode(image)` is not touched); camera loops should keep
     * one `QRScanner(width, height, options)` instead. Callbacks may re-enter [QRDecoder].
     *
     * @throws FinderNotFoundException when no finder triple was found
     * @throws InvalidFormatException when no format word was readable
     * @throws InvalidVersionException when the version words disagree with the sampled size
     * @throws QRDecodingException for every other failure (message = JS error tag)
     * @throws IllegalArgumentException for invalid options, image dimensions or data
     */
    fun decode(image: Image, options: DecodeOptions): String {
        val format = options.format ?: image.format
        QRScanner.validateImage(image.width, image.height, image.data, format, 0, 0, image.width, image.height)
        val s = QRScanner(image.width, image.height, options)
        try {
            s.addImage(image.width, image.height, image.data, format)
            val result = s.decode()
            if (result.isNotEmpty()) return result[0]
            throwFor(s.lastFailure ?: Fail.FINDER)
        } finally {
            s.clean()
        }
    }

    /**
     * decode.ts L3152-3175 (`decodeQRBatch`): decode every QR code in each image through one
     * reusable [maxWidth] x [maxHeight] scanner. Each result holds the texts plus the terminal
     * [Fail]; an image that throws (e.g. invalid or larger than the capacity) yields
     * `BatchResult(emptyList(), null, error)` without affecting its neighbours. Synchronous (JS
     * uses `decodeAsync(true)`; results are identical).
     */
    fun decodeBatch(
        images: List<Image>,
        options: DecodeOptions = DecodeOptions(),
        maxWidth: Int = 3840,
        maxHeight: Int = 3840,
    ): List<BatchResult> {
        // The default reusable 4K-square capacity is an API choice.
        val s = QRScanner(maxWidth, maxHeight, options)
        val results = ArrayList<BatchResult>(images.size)
        try {
            for (image in images) {
                results.add(
                    try {
                        s.addImage(image.width, image.height, image.data, options.format ?: image.format)
                        val texts = s.decode(all = true)
                        BatchResult(texts, s.lastFailure)
                    } catch (e: Exception) {
                        // A failed image must not discard successful results before or after it.
                        BatchResult(emptyList(), null, e)
                    }
                )
            }
        } finally {
            s.clean()
        }
        return results
    }

    private fun throwFor(fail: Fail): Nothing {
        throw when (fail) {
            Fail.FINDER -> FinderNotFoundException(fail.tag)
            Fail.FORMAT -> InvalidFormatException(fail.tag)
            Fail.VERSION -> InvalidVersionException(fail.tag)
            else -> QRDecodingException(fail.tag)
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
