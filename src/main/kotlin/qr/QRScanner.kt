package qr

import qr.Finder.Triple

/**
 * Reusable decode state (qr v0.7.2 `_QRScanner`, decode.ts L1318-1632): one input luma arena
 * sized for [maxWidth] x [maxHeight] and up to four pyramid [Layer]s, all allocated once.
 * Instances are not thread-safe; operations are exclusive.
 *
 * @param effort retry budget, >= 1 (`Int.MAX_VALUE` = JS Infinity)
 * @param timeLimitMs retry wall-time budget in ms, finite >= 0 or `Double.POSITIVE_INFINITY`
 * @param nativeLimit native-layer search gate (see JS `DecodeOpts.nativeLimit`), >= 0
 * @param textDecoder optional byte-segment decoder `(bytes, eci) -> text`. The callback runs during
 *   parsing, so it may be invoked for segments of a symbol whose later segment fails (JS defers it
 *   until the whole payload is parsed; the result is identical, only side-effect timing differs).
 */
class QRScanner(
    val maxWidth: Int,
    val maxHeight: Int,
    effort: Int = 1,
    timeLimitMs: Double = 1000.0 / 60,
    nativeLimit: Int = Int.MAX_VALUE,
    internal val textDecoder: ((ByteArray, Int) -> String)? = null,
) {
    /** Native luma arena (`maxWidth * maxHeight`, tight rows); layer 0 aliases it. */
    val luma: ByteArray

    /** Native-layer search gate; settable between frames (JS L1343). */
    var nativeLimit: Int = nativeLimit

    /** Staged image size. */
    var width = 0
        private set
    var height = 0
        private set

    internal val layers: List<Layer>
    internal val effort: Int
    internal val timeLimit: Double
    internal var staged = false
    internal var resized = false
    internal var blocked = 0
    internal var retryStart = 0.0
    internal var retries = 0

    // Homography phases do not overlap: `map` holds the active result while `from` and `to` are
    // scratch (L1349-1354).
    internal val map = DoubleArray(9)
    internal val from = DoubleArray(9)
    internal val to = DoubleArray(9)
    internal val tmp32 = IntArray(4 * 16 * 3 + 16)
    internal val tmp64 = DoubleArray(7 * 7 * 2 + (7 * 7 - 3) * 4)
    internal val remainder = IntArray(8)

    // Version-sized scratch, grown by reserve() to the largest symbol attempted.
    internal var grid = IntArray(0)
    internal var tmp8 = IntArray(0)
    internal var codewords = IntArray(0)
    internal val payload = Payload(textDecoder)
    internal val finePlane = Plane(ByteArray(0), IntArray(0), 0, 0, 0, 0)

    // Finder selection and projection reuse this triple by phase (L1336-1344).
    internal val triple = Triple()

    // JS `alignPoint` (L1356): the last located alignment center (findBasicAlign/searchAlign).
    internal var alignX = 0.1
    internal var alignY = 0.1

    // L1365-1366: read() flips dark for inverted symbols; the snapped size of the last projection.
    internal var invertedProjection = false
    internal var decodedSize = 0

    /** Last stage failure. */
    internal var fail: Fail = Fail.FINDER

    /** Failure tag of the scan that ended the last [decode], or null when it ended on a result. */
    var lastFailure: Fail? = null
        private set
    private var inFlight = false

    init {
        // decode.ts L1370-1465
        validateSize(maxWidth, maxHeight, "\"maxSize\"")
        val bytes = maxWidth.toLong() * maxHeight
        require(bytes <= MAX_ARENA_BYTES) { "input arena expected <= $MAX_ARENA_BYTES bytes, got ${maxWidth * maxHeight}*1" }
        require(effort >= 1) { "invalid effort=$effort" }
        require(timeLimitMs >= 0 && (timeLimitMs.isFinite() || timeLimitMs == Double.POSITIVE_INFINITY)) {
            "invalid timeLimit=$timeLimitMs"
        }
        require(nativeLimit >= 0) { "invalid nativeLimit=$nativeLimit" }
        this.effort = effort
        this.timeLimit = timeLimitMs
        luma = ByteArray(bytes.toInt())
        val list = ArrayList<Layer>(4)
        var w = maxWidth
        var h = maxHeight
        for (i in 0 until 4) {
            if (i > 0 && minOf(w, h) < 64) break
            list.add(Layer(i, w, h, if (i > 0) ByteArray(w * h) else luma))
            w = w shr 1
            h = h shr 1
        }
        layers = list
    }

    private inline fun <T> operation(body: () -> T): T {
        // decode.ts L1360-1368
        check(!inFlight) { "scanner operation already in flight" }
        inFlight = true
        try {
            return body()
        } finally {
            inFlight = false
        }
    }

    /** Convert one source image into the native luma layer; format inferred by [Image.format]. */
    fun addImage(image: Image) = addImage(image.width, image.height, image.data, image.format)

    /**
     * decode.ts L1520-1528: convert raw pixels into the native luma layer and stage them.
     * [format] null infers RGB/RGBA from an exact `3*px` / `4*px` length (gray is not inferred,
     * as in JS). [offset]/[stride] describe a strided layout (stride 0 = tight rows) and need [format].
     */
    fun addImage(
        width: Int,
        height: Int,
        data: ByteArray,
        format: PixelFormat? = null,
        offset: Int = 0,
        stride: Int = 0,
    ) = operation {
        copyLuma(width, height, data, format, offset, stride)
        stage(width, height)
    }

    /** decode.ts L1618-1632 (`processImage` with I420): luma already written into [luma], tight rows. */
    fun processLuma(width: Int, height: Int) = operation {
        copyLuma(width, height, luma, PixelFormat.GRAY, 0, width)
        stage(width, height)
    }

    /** decode.ts L1573-1615: zero every arena and reset per-image state. */
    fun clean() = operation {
        payload.clean()
        luma.fill(0)
        grid.fill(0)
        tmp8.fill(0)
        codewords.fill(0)
        tmp32.fill(0)
        tmp64.fill(0.0)
        remainder.fill(0)
        map.fill(0.0)
        from.fill(0.0)
        to.fill(0.0)
        for (li in layers.indices) {
            val layer = layers[li]
            layer.bitmap.fill(0)
            layer.blocks.fill(0)
            layer.cuts.fill(0)
            if (layer.luma !== luma) layer.luma.fill(0)
            layer.patterns.fill(0.0)
            layer.state.fill(0)
            layer.sets.fill(0.0)
            layer.blockHeight = 0
            layer.blockWidth = 0
            layer.height = 0
            layer.width = 0
            layer.words = 0
            layer.patternCount = 0
            layer.setCount = 0
            layer.setCursor = 0
            layer.used = false
            layer.search = false
            layer.found = false
            layer.setsReady = false
        }
        width = 0
        height = 0
        staged = false
        resized = false
        blocked = 0
    }

    // decode.ts L1531-1571: reset every per-image field after pixels become active.
    internal fun stage(width: Int, height: Int) {
        this.width = width
        this.height = height
        var aw = width
        var ah = height
        for (i in layers.indices) {
            val layer = layers[i]
            val used = i == 0 || minOf(aw, ah) >= 64
            layer.used = used
            // A frame too small for a half layer (shorter side under 128) always searches native.
            layer.search = used && (i > 0 || minOf(aw, ah) <= nativeLimit || minOf(aw, ah) < 128)
            layer.width = if (used) aw else 0
            layer.height = if (used) ah else 0
            layer.words = if (used) (aw + 31) / 32 else 0
            layer.blockWidth = if (used) (aw + 7) / 8 else 0
            layer.blockHeight = if (used) (ah + 7) / 8 else 0
            layer.patternCount = 0
            layer.plane.w = layer.width
            layer.plane.h = layer.height
            layer.plane.bw = layer.blockWidth
            layer.ox = 0.0
            layer.oy = 0.0
            layer.found = false
            layer.setCount = 0
            layer.setCursor = 0
            layer.setsReady = false
            layer.pickSum = -1.0
            aw = aw shr 1
            ah = ah shr 1
        }
        staged = true
        resized = false
        blocked = 0
        retryStart = 0.0
        retries = 0
    }

    /**
     * decode.ts L3100-3120 (`decode` -> `walk`): scan the staged frame. With [all] = false the
     * first scan's text (or nothing) is returned; with [all] = true scans repeat until one fails,
     * collecting every text and sharing one retry budget/deadline (JS also appends the terminal
     * Error; here it is [lastFailure]). Decoded regions stay excluded until the next image is staged.
     */
    fun decode(all: Boolean = false): List<String> = operation {
        retryStart = now()
        retries = if (effort == Int.MAX_VALUE) Int.MAX_VALUE else effort - 1
        val results = ArrayList<String>()
        while (true) {
            val result = scan()
            lastFailure = if (result == null) fail else null
            if (result == null) break
            results.add(result)
            if (!all) break
        }
        results
    }

    /**
     * decode.ts L2796-3097 (`scan`, cooperative = false): coarse-to-fine walk over the searchable
     * layers. Round 0 (mandatory, budget-free) attempts each layer's picked triple; later rounds
     * pop one scheduled retry set per layer while `retries` and `timeLimit` allow. Returns the
     * text, or null with [fail] = the last triple failure (initially [Fail.FINDER]).
     */
    internal fun scan(): String? {
        check(staged) { "expected addImage before decode" }
        var failed = Fail.FINDER
        ensureResized()
        // JS `walk: for (let mandatory = true; ; mandatory = false)`.
        var mandatory = true
        walk@ while (true) {
            var attempted = false
            for (i in layers.size - 1 downTo 0) {
                // L2825-2830 (`Date.now()` -> monotonic ms; Int.MAX_VALUE retries = Infinity).
                if (!mandatory &&
                    (retries == 0 ||
                        (timeLimit != Double.POSITIVE_INFINITY && now() - retryStart >= timeLimit))
                ) break@walk
                val layer = layers[i]
                if (!layer.used || !layer.search) continue
                prepareLayer(layer)
                val triple = if (mandatory) pickMandatory(layer)
                else {
                    buildSchedule(layer)
                    nextSet(layer)
                } ?: continue
                if (!mandatory && retries != Int.MAX_VALUE) retries--
                attempted = true
                // Large blurred symbols get template-fit refinement first; other projection failures
                // retry once after a cross refinement pass.
                var pitch = if (blocked == 0 && Math.abs(triple.tr.y - triple.tl.y) > Math.abs(triple.tr.x - triple.tl.x))
                    Finder.edgePitch(layer, triple.tl, triple.tr, triple.inverted)
                else 0.0
                val fit = pitch != 0.0 && !pitch.isNaN() &&
                    Homography.distance(triple.tl.x, triple.tl.y, triple.tr.x, triple.tr.y) / pitch + 7 >= 21 + (7 - 1) * 4
                if (fit) {
                    Finder.fitPattern(layer, triple.tl, triple.inverted)
                    Finder.fitPattern(layer, triple.tr, triple.inverted)
                    Finder.fitPattern(layer, triple.bl, triple.inverted)
                    Finder.refineTriple(layer, triple)
                    pitch = Finder.edgePitch(layer, triple.tl, triple.tr, triple.inverted)
                }
                var done = projectWith(layer, triple, pitch)
                if (done == null && !fit && Finder.refineTriple(layer, triple)) done = projectWith(layer, triple, pitch)
                if (done != null) {
                    // L3058-3086. JS calls `imageOnResult` / `pointsOnDetect` here (not ported).
                    val padding = 3.5 / (decodedSize - 7)
                    val p = layer.patterns
                    val tl = 4 * triple.tlIndex
                    val tr = 4 * triple.trIndex
                    val bl = 4 * triple.blIndex
                    // Retain the projected QR region in its dead finder records instead of allocating.
                    p[tl] = triple.tl.x
                    p[tl + 1] = triple.tl.y
                    p[tl + 2] = triple.trIndex.toDouble()
                    p[tl + 3] = triple.blIndex.toDouble()
                    p[tr] = triple.tr.x
                    p[tr + 1] = triple.tr.y
                    p[tr + 2] = padding
                    p[bl] = triple.bl.x
                    p[bl + 1] = triple.bl.y
                    val state = layer.state
                    if ((state[triple.tlIndex].toInt() and 2) == 0) blocked++
                    if ((state[triple.trIndex].toInt() and 2) == 0) blocked++
                    if ((state[triple.blIndex].toInt() and 2) == 0) blocked++
                    state[triple.tlIndex] = (state[triple.tlIndex].toInt() or (2 or 4)).toByte()
                    state[triple.trIndex] = (state[triple.trIndex].toInt() or (2 or 8)).toByte()
                    state[triple.blIndex] = (state[triple.blIndex].toInt() or (2 or 8)).toByte()
                    for (li in layers.indices) {
                        val source = layers[li]
                        if (!source.found) continue
                        exclude(source)
                    }
                    return done
                }
                failed = fail
            }
            if (!mandatory && !attempted) break
            mandatory = false
        }
        fail = failed
        return null
    }

    // JS `Date.now()` for the retry deadline; monotonic milliseconds (only differences are used).
    private fun now(): Double = System.nanoTime() / 1e6

    // decode.ts L2801-2815: build the pyramid below the native layer once per staged frame.
    internal fun ensureResized() {
        if (resized) return
        for (i in 1 until layers.size) {
            val layer = layers[i]
            if (!layer.used) break
            layer.resizeFrom(layers[i - 1])
        }
        resized = true
    }

    // decode.ts L2835-2860: threshold a layer and search it for finders, once per staged frame.
    internal fun prepareLayer(layer: Layer) {
        if (layer.found) return
        layer.computeBlocks()
        layer.computeBitmap()
        layer.patternCount = 0
        Finder.find(layer)
        layer.found = true
    }

    // decode.ts L553-606 (validateImage) + L608-695 (copyLuma) as plain loops; the word-wise
    // copyWords/copyTriples fast paths compute the same (r + 2g + b) >> 2.
    private fun copyLuma(width: Int, height: Int, data: ByteArray, named: PixelFormat?, offset: Int, stride: Int) {
        validateSize(width, height, "\"img\"")
        require(width <= maxWidth && height <= maxHeight) {
            "\"img\" expected dimensions <= ${maxWidth}x$maxHeight, got ${width}x$height"
        }
        val px = width * height
        val layout = offset != 0 || stride != 0
        val format: PixelFormat
        if (named != null) {
            format = named
            val step = step(format)
            if (layout) {
                val row = step * width
                val s = if (stride == 0) row else stride
                val end = offset.toLong() + (height - 1).toLong() * s + row
                require(offset >= 0 && s >= row && end <= data.size) {
                    "\"img.data\" expected valid offset/stride for format=$named, " +
                        "got offset=$offset, stride=$stride, length=${data.size}"
                }
            } else {
                val expected = step * px
                val planar = step == 1
                require(!((planar && data.size < expected) || (!planar && data.size != expected))) {
                    "\"img.data\" expected ${if (planar) "at least " else ""}$expected bytes for format=$named, got ${data.size}"
                }
            }
        } else {
            require(!layout) { "\"img.data\" offset/stride require an explicit format" }
            format = when (data.size) {
                3 * px -> PixelFormat.RGB
                4 * px -> PixelFormat.RGBA
                else -> throw IllegalArgumentException(
                    "\"img.data\" expected ${3 * px} or ${4 * px} bytes without format, got ${data.size}"
                )
            }
        }
        val step = step(format)
        val rowStride = if (stride == 0) width * step else stride
        val out = luma
        if (data === out && offset == 0 && rowStride == width && step == 1) return
        if (step == 1 && rowStride == width) {
            System.arraycopy(data, offset, out, 0, px)
            return
        }
        for (y in 0 until height) {
            var src = offset + y * rowStride
            var dst = y * width
            if (step == 1) {
                System.arraycopy(data, src, out, dst, width)
            } else {
                for (x in 0 until width) {
                    out[dst++] = (((data[src].toInt() and 0xFF) + 2 * (data[src + 1].toInt() and 0xFF) +
                        (data[src + 2].toInt() and 0xFF)) shr 2).toByte()
                    src += step
                }
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_SIDE = 4096
        const val MAX_ARENA_BYTES = 64 * 1024 * 1024

        fun step(format: PixelFormat): Int = when (format) {
            PixelFormat.GRAY -> 1
            PixelFormat.RGB -> 3
            PixelFormat.RGBA -> 4
        }

        // decode.ts L142-152
        fun validateSize(width: Int, height: Int, name: String) {
            require(width > 0 && height > 0) { "$name expected positive width and height" }
            require(width <= MAX_IMAGE_SIDE && height <= MAX_IMAGE_SIDE) {
                "$name expected width and height <= $MAX_IMAGE_SIDE, got ${width}x$height"
            }
        }
    }
}
