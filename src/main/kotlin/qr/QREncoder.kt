package qr

/**
 * QR encoder: port of paulmillr/qr v0.7.2 `encodeQR` (index.ts L1162-1270).
 *
 * Invalid options throw [IllegalArgumentException] with the JS message text (JS throws
 * Error/RangeError/TypeError). JS checks that Kotlin types make impossible are dropped: `asString`
 * on text/output, the opts-object check, ecc/encoding membership, safe-integer/fractional numbers
 * (`asNum`/`asVersion` type checks) and `_isBytes` on the textEncoder result.
 */
object QREncoder {
    // index.ts Output L25-26 + maxOutputSize L1246-1249: compact outputs allow 4x the side.
    private enum class Output(val maxSize: Int) { RAW(1024), ASCII(4096), TERM(1024), SVG(1024), GIF(4096), DATA_URL(4096) }

    /** `encodeQR(text, 'raw', opts)`: rows `[y][x]`, true = dark, border (and scale) included. */
    fun encodeRaw(text: String, options: EncodeOptions = EncodeOptions()): Array<BooleanArray> =
        Renderers.raw(prepare(text, options, Output.RAW))

    /**
     * `encodeQR(text, 'ascii', opts)`: two output rows per line in half-block glyphs (dark modules
     * print as spaces, for dark-on-light terminals), every line ending in `\n`.
     */
    fun encodeAscii(text: String, options: EncodeOptions = EncodeOptions()): String =
        Renderers.ascii(prepare(text, options, Output.ASCII))

    /** `encodeQR(text, 'term', opts)`: two ANSI-colored spaces per pixel, `\n` after every row. */
    fun encodeTerm(text: String, options: EncodeOptions = EncodeOptions()): String =
        Renderers.term(prepare(text, options, Output.TERM))

    /** `encodeQR(text, 'svg', opts)`: one `<path>` when [EncodeOptions.optimize], else one `<rect>` per dark pixel. */
    fun encodeSvg(text: String, options: EncodeOptions = EncodeOptions()): String =
        Renderers.svg(prepare(text, options, Output.SVG), options.optimize)

    /** `encodeQR(text, 'gif', opts)`: GIF87a bytes, one pixel per output pixel. */
    fun encodeGif(text: String, options: EncodeOptions = EncodeOptions()): ByteArray =
        Renderers.gif(prepare(text, options, Output.GIF))

    /** `encodeQR(text, 'data-url', opts)`: the GIF as a `data:image/gif;base64,` URL. */
    fun encodeDataUrl(text: String, options: EncodeOptions = EncodeOptions()): String =
        Renderers.dataUrl(Renderers.gif(prepare(text, options, Output.DATA_URL)))

    // index.ts encodeQR L1175-1258: validation, version selection, encoding, drawing, geometry.
    private fun prepare(text: String, o: EncodeOptions, output: Output): Raster {
        // L1179-1180: an explicit version is validated before caller-controlled encoding (asVersion L351-356).
        o.version?.let { require(it in 1..40) { "Invalid version=$it. Expected number [1..40]" } }
        val ecc = o.ecc
        // L1183-1195
        val encoding = o.encoding ?: Segments.detectType(text)
        if (encoding != Encoding.BYTE) {
            val limit = if (encoding == Encoding.NUMERIC) 10 else QRInfo.ALPHANUMERIC.length
            for (i in text.indices) {
                val c = text[i].code
                val v = if (c < 128) Segments.ALNUM_VAL[c] else -1
                require(v in 0 until limit) {
                    "Unknown letter: \"${String(Character.toChars(text.codePointAt(i)))}\". " +
                        "Allowed: ${QRInfo.ALPHANUMERIC.substring(0, limit)}"
                }
            }
        }
        // L1196-1197
        o.mask?.let { require(it in 0..7) { "invalid mask=$it" } }
        // L1198-1205: reject impossible built-in UTF-8 payloads before encoding a huge input.
        val textEncoder = o.textEncoder
        if (encoding == Encoding.BYTE && textEncoder == null) {
            val maxBytes = Segments.byteCapacity(o.version ?: 40, ecc)
            require(!(text.length > maxBytes || Utf8.length(text) > maxBytes)) { "Capacity overflow" }
        }
        // L1206-1210
        val utf8 = if (encoding == Encoding.BYTE) (textEncoder ?: Utf8::encode)(text) else null
        // L1211-1217
        val dataLen = utf8?.size ?: text.length
        val encodedBits = when (encoding) {
            Encoding.NUMERIC -> dataLen / 3 * 10 + intArrayOf(0, 4, 7)[dataLen % 3]
            Encoding.ALPHANUMERIC -> dataLen / 2 * 11 + (dataLen % 2) * 6
            Encoding.BYTE -> dataLen * 8
        }
        // L1218-1230
        fun fits(v: Int): Boolean {
            val lengthBits = Segments.lengthBits(v, encoding)
            return dataLen < (1 shl lengthBits) && 4 + lengthBits + encodedBits <= Segments.capacity(v, ecc).capacity
        }
        val ver: Int
        if (o.version == null) {
            var v = 1
            while (v <= 40) {
                if (fits(v)) break
                v++
            }
            require(v <= 40) { "Capacity overflow" }
            ver = v
        } else {
            ver = o.version
            require(fits(ver)) { "Capacity overflow" }
        }
        // L1231-1232
        val data = Segments.encodeData(ver, ecc, text, encoding, utf8)
        val m = Symbol.draw(ver, ecc, data, o.mask)
        // L1233-1241: border/scale are checked after encoding, so a capacity error wins.
        val border = o.border
        require(border > 0) { "invalid border=$border" }
        val scale = o.scale
        require(scale in 1..1024) { "invalid scale factor: $scale" }
        // L1242-1253
        val size = m.size
        val wl = (size + 2L * border) * scale
        require(wl <= output.maxSize) { "invalid opts: output is ${wl}x$wl (max ${output.maxSize}), reduce border/scale" }
        val w = wl.toInt()
        // L1254-1258
        val map = IntArray(w) { i ->
            val f = i / scale - border
            if (f in 0 until size) f else -1
        }
        return Raster(m, w, map)
    }
}
