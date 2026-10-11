package qr

/**
 * Luma plane plus its 8x8 threshold grid, read by projection (decode.ts L166-173 `Plane`;
 * JS `W`/`H` -> [w]/[h]). [sh] is log2 of the threshold block side (3 on the owning layer).
 */
internal class Plane(
    var d: ByteArray,
    var cut: IntArray,
    var w: Int,
    var h: Int,
    var bw: Int,
    var sh: Int,
)

/**
 * One pyramid layer (decode.ts `_QRLayer` L105-118 + `ScannerLayer` L480-498, built by the
 * `_QRScanner` constructor body L1408-1463). Arrays are sized once for the scanner's maximum
 * image at this scale; per-frame dimensions live in [width]/[height]/[words]/[blockWidth]/[blockHeight].
 *
 * Typed-array mapping: `bitmap` Int32Array -> IntArray (32 px per word, bit `x and 31` of word
 * `x ushr 5`); `blocks` Uint8Array -> IntArray 0..255; `cuts` Int16Array -> IntArray;
 * `inverted` Uint8Array -> [state] ByteArray.
 */
internal class Layer(val index: Int, maxWidth: Int, maxHeight: Int, luma: ByteArray) {
    /** Layer 0: the scanner's input arena; other layers: own `maxWidth * maxHeight` buffer. */
    val luma: ByteArray = luma
    val bitmap = IntArray(ceilDiv(maxWidth, 32) * maxHeight)
    val blocks = IntArray(ceilDiv(maxWidth, 8) * ceilDiv(maxHeight, 8))
    val cuts = IntArray(ceilDiv(maxWidth, 8) * ceilDiv(maxHeight, 8))

    // Finder records start small and grow on demand, up to one per 7x7 cell (L1414-1415).
    private val centers = minOf(64, ceilDiv(maxWidth, 7) * ceilDiv(maxHeight, 7))

    /** Stride-4 finder records: x, y, ms, count. */
    var patterns = DoubleArray(centers * 4)

    /** JS `inverted`: bit0 inverted polarity, bit1 excluded/consumed, bit2/bit3 decoded-region roles. */
    var state = ByteArray(centers)
    var patternCount = 0
    var width = 0
    var height = 0
    var words = 0
    var blockWidth = 0
    var blockHeight = 0
    var used = false

    /** Finder search runs on this layer; false on a native layer past nativeLimit. */
    var search = false
    var found = false
    val plane = Plane(luma, cuts, maxWidth, maxHeight, ceilDiv(maxWidth, 8), 3)

    // JS `context` (Ctx L462-470): scale factor 2^index and the crop origin.
    val scale = 1 shl index
    var ox = 0.0
    var oy = 0.0

    // Stride-5 retry records: [rank, inverted, finder0, finder1, finder2].
    val sets = DoubleArray(256 * 5)
    var setCount = 0
    var setCursor = 0
    var setsReady = false
    var pickSum = -1.0
    var pickLo = 0.0
    var pickHi = 0.0

    // decode.ts L719-722: packed bitmap bit; -1 out of bounds.
    fun bit(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return -1
        return (bitmap[y * words + (x ushr 5)] ushr (x and 31)) and 1
    }

    /**
     * decode.ts L991-1035 `scanRows.resize(src.luma, luma, src.width, width, 0, height)`: 2x2 box
     * filter with rounding. Ports the scalar loop L1025-1033; the word-aligned SWAR path
     * L1001-1024 sums the same four bytes in 16-bit lanes and is bit-identical.
     */
    fun resizeFrom(src: Layer) {
        val s = src.luma
        val d = luma
        val srcWidth = src.width
        val dstWidth = width
        for (y in 0 until height) {
            var srcPos = (y shl 1) * srcWidth
            var dstPos = y * dstWidth
            for (x in 0 until dstWidth) {
                d[dstPos++] = (((s[srcPos].toInt() and 0xFF) + (s[srcPos + 1].toInt() and 0xFF) +
                    (s[srcPos + srcWidth].toInt() and 0xFF) + (s[srcPos + srcWidth + 1].toInt() and 0xFF) + 2) shr 2).toByte()
                srcPos += 2
            }
        }
    }

    /**
     * decode.ts L1036-1097 `scanRows.blocks(layer, 0, blockHeight)`: one threshold per 8x8 block,
     * with the low-contrast rule (`max - min <= 24` -> `min / 2`, or the weighted predecessor
     * `(2 * top + left + topLeft) / 4` when larger than `min`) and the `>>> 0` Uint8 store.
     *
     * A block reaches past the arena only for a layer narrower or shorter than 8 px; JS then reads
     * `undefined` (sum -> NaN, min/max comparisons false). [blockSlow] replicates that.
     */
    fun computeBlocks() {
        if (width < 8 || height < 8) {
            blockSlow()
            return
        }
        val brightness = luma
        val bWidth = blockWidth
        val maxY = height - 8
        val maxX = width - 8
        for (y in 0 until blockHeight) {
            val yPos = cap(y * 8, maxY)
            for (x in 0 until bWidth) {
                val xPos = cap(x * 8, maxX)
                var sum = 0
                var min = 0xff
                var max = 0
                var pos = yPos * width + xPos
                for (yy in 0 until 8) {
                    for (xx in 0 until 8) {
                        val p = brightness[pos + xx].toInt() and 0xFF
                        sum += p
                        if (p < min) min = p
                        if (p > max) max = p
                    }
                    pos += width
                }
                // Math.floor(sum / 64) on a non-negative integer sum.
                var average = (sum / 64).toDouble()
                if (max - min <= 24) {
                    average = min / 2.0
                    if (y > 0 && x > 0) {
                        val top = blocks[(y - 1) * bWidth + x]
                        val left = blocks[y * bWidth + x - 1]
                        val topLeft = blocks[(y - 1) * bWidth + x - 1]
                        val previous = (2 * top + left + topLeft) / 4.0
                        if (min < previous) average = previous
                    }
                }
                blocks[bWidth * y + x] = average.toInt() // `>>> 0` of a non-negative finite value
            }
        }
    }

    // L1036-1097 for layers under 8 px on a side: bounds-checked reads with JS undefined semantics.
    private fun blockSlow() {
        val brightness = luma
        val bWidth = blockWidth
        val maxY = height - 8
        val maxX = width - 8
        for (y in 0 until blockHeight) {
            val yPos = cap(y * 8, maxY)
            for (x in 0 until bWidth) {
                val xPos = cap(x * 8, maxX)
                var sum = 0.0
                var min = 0xff
                var max = 0
                var pos = yPos * width + xPos
                for (yy in 0 until 8) {
                    for (xx in 0 until 8) {
                        val i = pos + xx
                        if (i >= brightness.size) {
                            sum = Double.NaN // undefined + ... -> NaN; `undefined < min` is false
                            continue
                        }
                        val p = brightness[i].toInt() and 0xFF
                        sum += p
                        if (p < min) min = p
                        if (p > max) max = p
                    }
                    pos += width
                }
                var average = Math.floor(sum / 64)
                if (max - min <= 24) {
                    average = min / 2.0
                    if (y > 0 && x > 0) {
                        val top = blocks[(y - 1) * bWidth + x]
                        val left = blocks[y * bWidth + x - 1]
                        val topLeft = blocks[(y - 1) * bWidth + x - 1]
                        val previous = (2 * top + left + topLeft) / 4.0
                        if (min < previous) average = previous
                    }
                }
                blocks[bWidth * y + x] = if (average.isNaN()) 0 else average.toInt() // NaN >>> 0 == 0
            }
        }
    }

    /**
     * `bitmap.fill(0, 0, words * height)` then decode.ts L1098-1182 `scanRows.bitmap(layer, 0,
     * blockHeight)`: 5x5 block-mean threshold (window clamped two blocks inside each edge) and
     * packed writes of each 8x8 block. Ports the scalar compare L1150-1151 (`luma <= sum / 25`);
     * the `lumaWords` SWAR path is bit-identical and is not ported.
     *
     * OOB rule: JS reads `blocks[i]` past the typed array when blockWidth or blockHeight is
     * under 5 (indices wrap into the next block row, and the last ones run off the arena). Those
     * reads yield `undefined`, so the window sum is NaN: `cuts` stores `Math.floor(NaN)` into an
     * Int16Array as 0 and `luma <= NaN` is false for every pixel (even luma 0), leaving the block
     * light. Column sums are therefore kept as doubles over a bounds-checked read that returns NaN
     * past `blocks.size`; in-range wrapped reads use the stale/neighbour values JS sees.
     */
    fun computeBitmap() {
        bitmap.fill(0, 0, words * height)
        val brightness = luma
        val bWidth = blockWidth
        val bHeight = blockHeight
        val maxY = height - 8
        val maxX = width - 8
        // Pixel and bitmap accesses stay in range unless the layer is under 8 px on a side.
        val checked = width < 8 || height < 8
        for (y in 0 until bHeight) {
            val yPos = cap(y * 8, maxY)
            val top = cap2(y, bHeight - 3)
            val row = bWidth * (top - 2)
            var c0 = column(row, bWidth)
            var c1 = column(row + 1, bWidth)
            var c2 = column(row + 2, bWidth)
            var c3 = column(row + 3, bWidth)
            var c4 = column(row + 4, bWidth)
            var sum = c0 + c1 + c2 + c3 + c4
            for (x in 0 until bWidth) {
                val xPos = cap(x * 8, maxX)
                if (x > 2 && x <= bWidth - 3) {
                    c0 = c1
                    c1 = c2
                    c2 = c3
                    c3 = c4
                    c4 = column(row + x + 2, bWidth)
                    sum = c0 + c1 + c2 + c3 + c4
                }
                val average = sum / 25
                val cut = Math.floor(average)
                cuts[y * bWidth + x] = if (cut.isNaN()) 0 else cut.toInt() // Int16Array store, 0..255
                // For integer luma, `v <= average` == `v <= floor(average)`; NaN -> -1 (nothing dark).
                val dark = if (cut.isNaN()) -1 else cut.toInt()
                var pos = yPos * width + xPos
                val shift = xPos and 31
                var word = yPos * words + (xPos ushr 5)
                val lowMask = 0xff shl shift
                for (yy in 0 until 8) {
                    var value = 0
                    for (xx in 0 until 8) {
                        val i = pos + xx
                        if (checked && i >= brightness.size) continue // undefined <= average is false
                        if ((brightness[i].toInt() and 0xFF) <= dark) value = value or (1 shl xx)
                    }
                    // Typed-array writes past the arena are dropped (only reachable when `checked`).
                    if (word < bitmap.size) bitmap[word] = (bitmap[word] and lowMask.inv()) or (value shl shift)
                    if (shift > 24 && word + 1 < bitmap.size) {
                        val highMask = (1 shl (shift - 24)) - 1
                        bitmap[word + 1] = (bitmap[word + 1] and highMask.inv()) or (value ushr (32 - shift))
                    }
                    pos += width
                    word += words
                }
            }
        }
    }

    // Five stacked blocks starting at `i` (L1120-1129 / L1135-1141), NaN when any is past the arena.
    private fun column(i: Int, bWidth: Int): Double =
        blockAt(i) + blockAt(i + bWidth) + blockAt(i + 2 * bWidth) + blockAt(i + 3 * bWidth) + blockAt(i + 4 * bWidth)

    private fun blockAt(i: Int): Double = if (i < blocks.size) blocks[i].toDouble() else Double.NaN

    /**
     * decode.ts L938-951: double the finder records, capped at one per 7x7 cell of the staged
     * frame. Throws when already at the cap.
     */
    fun growFinders() {
        val centers = ceilDiv(width, 7) * ceilDiv(height, 7)
        val count = state.size
        if (count >= centers) throw IllegalStateException("finder storage exhausted at ${width}x$height")
        val records = minOf(centers, count * 2)
        patterns = patterns.copyOf(records * 4)
        state = state.copyOf(records)
    }

    private companion object {
        fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        // JS cap(value, 0, max) (L121-126): min first, then max.
        fun cap(value: Int, max: Int): Int = maxOf(minOf(value, max), 0)

        // JS cap(value, 2, max).
        fun cap2(value: Int, max: Int): Int = maxOf(minOf(value, max), 2)
    }
}
