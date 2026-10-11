package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `pointsOnDetect` geometry reporting (decode.ts L2084-2152, L2638-2643, L2755-2773, L3087, L3095). */
class PointsTest {
    private fun p(x: Int, y: Int) = Point(x.toDouble(), y.toDouble())
    private fun quad(vararg xy: Int): Quad = (xy.indices step 2).map { p(xy[it], xy[it + 1]) }
    private fun fp(x: Int, y: Int, ms: Int, vararg corners: Int) =
        FinderPoint(x.toDouble(), y.toDouble(), ms.toDouble(), quad(*corners))

    // JS Math.round: floor(x + 0.5).
    private fun round(v: Double): Long = Math.floor(v + 0.5).toLong()
    private fun rq(q: Quad) = q.map { listOf(round(it.x), round(it.y)) }

    // decode.test.ts L517-596
    @Test
    fun `reports finder-derived bounds and detected aligners`() {
        val raw = TestEncoder.encodeRaw("OVERLAY", version = 2, ecc = ErrorCorrection.MEDIUM, mask = 0, border = 4)
        val img = TestImages.matrixToImage(raw, 4)
        assertEquals(132, img.width)
        val events = ArrayList<Pair<FinderPoints, DecodeResult>>()
        val text = QRDecoder.decode(img, DecodeOptions(pointsOnDetect = { points, result -> events.add(points to result) }))
        assertEquals("OVERLAY", text)
        assertEquals(1, events.size)
        assertEquals(DecodeResult.Text("OVERLAY"), events[0].second)
        val expected = FinderPoints(
            tl = fp(30, 30, 4, 16, 16, 44, 16, 44, 44, 16, 44),
            tr = fp(102, 30, 4, 88, 16, 116, 16, 116, 44, 88, 44),
            br = p(102, 102),
            bl = fp(30, 102, 4, 16, 88, 44, 88, 44, 116, 16, 116),
            aligners = listOf(fp(90, 90, 4, 80, 80, 100, 80, 100, 100, 80, 100)),
            bounds = quad(16, 16, 116, 16, 116, 116, 16, 116),
            outline = quad(12, 12, 120, 12, 120, 120, 12, 120),
            boundingBox = BoundingBox(16.0, 16.0, 100.0, 100.0),
        )
        assertEquals(expected, events[0].first)
    }

    // decode.test.ts L598-663
    @Test
    fun `reports squeezed finder and outline geometry on perspective failure`() {
        var points: FinderPoints? = null
        var terminal: DecodeResult? = null
        val img = TestImages.readLuma("boofcv-v3/detection/perspective/image022.jpg")
        val e = assertThrows<QRDecodingException> {
            QRDecoder.decode(img, DecodeOptions(pointsOnDetect = { value, result ->
                points = value
                terminal = result
            }))
        }
        assertEquals("rs", e.message)
        val pts = points!!
        val box = pts.boundingBox
        assertEquals(
            mapOf(
                "result" to terminal,
                "tl" to rq(pts.tl.corners),
                "tr" to rq(pts.tr.corners),
                "bl" to rq(pts.bl.corners),
                "aligners" to pts.aligners.map { rq(it.corners) },
                "bounds" to rq(pts.bounds),
                "outline" to rq(pts.outline),
                "boundingBox" to listOf(round(box.x), round(box.y), round(box.width), round(box.height)),
            ),
            mapOf(
                "result" to DecodeResult.Failure(Fail.RS),
                "tl" to listOf(listOf(438L, 259L), listOf(467L, 306L), listOf(433L, 334L), listOf(404L, 287L)),
                "tr" to listOf(listOf(512L, 380L), listOf(541L, 426L), listOf(508L, 454L), listOf(479L, 408L)),
                "bl" to listOf(listOf(352L, 331L), listOf(381L, 378L), listOf(347L, 406L), listOf(318L, 359L)),
                "aligners" to emptyList<Any>(),
                "bounds" to listOf(listOf(438L, 259L), listOf(541L, 426L), listOf(422L, 526L), listOf(318L, 359L)),
                "outline" to listOf(listOf(438L, 248L), listOf(550L, 429L), listOf(421L, 537L), listOf(309L, 356L)),
                "boundingBox" to listOf(318L, 259L, 223L, 267L),
            ),
        )
    }

    // decode.test.ts L665-736
    @Test
    fun `samples a warped high-version symbol through alignment tiles`() {
        val payload = "TILED PROJECTION ".repeat(5)
        val version = 7
        val border = 4
        val scale = 8
        val raw = TestEncoder.encodeRaw(payload, version = version, border = border, ecc = ErrorCorrection.LOW, mask = 0)
        val modules = 17 + 4 * version
        val width = raw[0].size * scale
        val height = raw.size * scale
        assertEquals(424, width)
        val data = ByteArray(width * height)
        // One global homography cannot follow this smooth interior displacement;
        // the alignment lattice still provides local registration for each tile.
        for (py in 0 until height)
            for (px in 0 until width) {
                val x = (px + 0.5) / scale - border
                val y = (py + 0.5) / scale - border
                // V8 Math.sin is fdlibm = StrictMath.sin.
                val dx = 0.75 * StrictMath.sin((Math.PI * x) / modules) * StrictMath.sin((Math.PI * y) / modules)
                val sx = Math.floor(x - dx).toInt() + border
                val sy = Math.floor(y).toInt() + border
                // JS `raw[sy]?.[sx] ? 0 : 255` (out of range = light).
                val dark = sy in raw.indices && sx in raw[sy].indices && raw[sy][sx]
                data[py * width + px] = (if (dark) 0 else 255).toByte()
            }
        var points: FinderPoints? = null
        val result = QRDecoder.decode(
            Image(width, height, data),
            DecodeOptions(format = PixelFormat.I420, pointsOnDetect = { value, _ -> points = value }),
        )
        val pts = points!!
        fun point(v: FinderPoint) = listOf(round(v.x), round(v.y), round(v.moduleSize))
        fun point(v: Point) = listOf(round(v.x), round(v.y))
        assertEquals(
            listOf<Any>(
                payload,
                point(pts.tl), point(pts.tr), point(pts.br), point(pts.bl),
                pts.aligners.map { point(it) },
                pts.boundingBox,
            ),
            listOf<Any>(
                result,
                listOf(60L, 60L, 8L), listOf(364L, 60L, 8L), listOf(364L, 364L), listOf(60L, 364L, 8L),
                listOf(
                    listOf(212L, 84L, 8L), listOf(84L, 212L, 8L), listOf(216L, 212L, 8L),
                    listOf(340L, 212L, 8L), listOf(212L, 340L, 8L), listOf(340L, 340L, 8L),
                ),
                // The synthetic displacement is zero at every outer QR edge.
                BoundingBox(32.0, 32.0, 360.0, 360.0),
            ),
        )
    }

    // decode-batch.test.ts L48-83
    @Test
    fun `reports one terminal success or failure rather than projection attempts`() {
        val raw = TestEncoder.encodeRaw("A", version = 1, ecc = ErrorCorrection.LOW, mask = 0, border = 4)
        val good = TestImages.matrixToImage(raw, 4)
        val failedRaw = Array(raw.size) { raw[it].copyOf() }
        for (y in 12 until 16) for (x in 12 until 16) failedRaw[y][x] = !failedRaw[y][x]
        val failed = TestImages.matrixToImage(failedRaw, 4)
        val blank = Image(good.width, good.height, ByteArray(good.data.size) { 255.toByte() })
        val events = ArrayList<DecodeResult>()
        val options = DecodeOptions(pointsOnDetect = { _, result -> events.add(result) })
        val failures = ArrayList<String>()
        for (input in listOf(failed, blank)) {
            try {
                QRDecoder.decode(input, options)
            } catch (e: QRDecodingException) {
                failures.add(e.message!!)
            }
        }
        val decoded = QRDecoder.decode(good, options)
        assertEquals("A", decoded)
        assertEquals(listOf("timing", "finder"), failures)
        assertEquals(listOf(DecodeResult.Failure(Fail.TIMING), DecodeResult.Text("A")), events)
    }

    // decode-batch.test.ts L21-46
    @Test
    fun `batch fires once per image`() {
        fun image(text: String, scale: Int) = TestImages.matrixToImage(TestEncoder.encodeRaw(text, border = 4), scale)
        fun pad(image: Image, width: Int): Image {
            val data = ByteArray(width * image.height * 4) { 255.toByte() }
            val offset = (width - image.width) shr 1
            for (y in 0 until image.height)
                System.arraycopy(image.data, y * image.width * 4, data, (y * width + offset) * 4, image.width * 4)
            return Image(width, image.height, data)
        }
        val images = listOf(
            pad(image("BATCH SMALL", 4), 180),
            pad(image("BATCH MEDIUM", 7), 260),
            pad(image("BATCH LARGE", 11), 420),
        )
        var detected = 0
        val results = QRDecoder.decodeBatch(images, DecodeOptions(pointsOnDetect = { _, _ -> detected++ }), 512, 512)
        assertEquals(
            images.map { listOf(QRDecoder.decode(it), "finder") },
            results.map { r -> r.texts + listOfNotNull(r.failure?.tag ?: r.error?.message) },
        )
        assertEquals(images.size, detected)
    }
}
