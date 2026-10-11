package qr

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RendererTest {
    private data class Case(val text: String, val opts: EncodeOptions, val output: String, val value: Any?, val line: String)

    private val cases: List<Case> by lazy {
        javaClass.getResourceAsStream("/encoder-renders.txt")!!.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                @Suppress("UNCHECKED_CAST")
                val o = MiniJson.parse(line) as Map<String, Any?>
                val opts = o["opts"] as Map<String, Any?>
                for (k in opts.keys) check(k in setOf("optimize", "scale", "border")) { "unsupported opt $k" }
                val e = EncodeOptions(
                    optimize = opts["optimize"] as Boolean? ?: true,
                    scale = (opts["scale"] as Double?)?.toInt() ?: 1,
                    border = (opts["border"] as Double?)?.toInt() ?: 2,
                )
                Case(o["text"] as String, e, o["output"] as String, o["value"], line.take(120))
            }
    }

    @Test
    fun `every JS render fixture matches`() {
        assertEquals(10, cases.size)
        for (c in cases) {
            when (c.output) {
                "svg" -> assertEquals(c.value, QREncoder.encodeSvg(c.text, c.opts), c.line)
                "term" -> assertEquals(c.value, QREncoder.encodeTerm(c.text, c.opts), c.line)
                "ascii" -> assertEquals(c.value, QREncoder.encodeAscii(c.text, c.opts), c.line)
                "data-url" -> assertEquals(c.value, QREncoder.encodeDataUrl(c.text, c.opts), c.line)
                "raw" -> {
                    val rows = QREncoder.encodeRaw(c.text, c.opts).map { r -> r.joinToString("") { if (it) "1" else "0" } }
                    assertEquals(c.value, rows, c.line)
                }
                else -> error("unknown output ${c.output}")
            }
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    /** Parses the GIF layout and returns the pixel indices (clear codes stripped). */
    private fun checkGifStructure(gif: ByteArray, w: Int): ByteArray {
        assertEquals("GIF87a", String(gif, 0, 6, Charsets.US_ASCII))
        assertEquals(w, u16(gif, 6))
        assertEquals(w, u16(gif, 8))
        assertEquals(0xF6, gif[10].toInt() and 0xff)
        assertEquals(0, gif[11].toInt())
        assertEquals(0, gif[12].toInt())
        for (i in 13 until 16) assertEquals(0xFF, gif[i].toInt() and 0xff)
        for (i in 16 until 16 + 3 * 127) assertEquals(0, gif[i].toInt())
        var p = 16 + 3 * 127
        assertEquals(0x2C, gif[p++].toInt())
        for (i in 0 until 4) assertEquals(0, gif[p++].toInt())
        assertEquals(w, u16(gif, p)); p += 2
        assertEquals(w, u16(gif, p)); p += 2
        assertEquals(0, gif[p++].toInt())
        assertEquals(7, gif[p++].toInt())
        assertEquals(408, p)
        val pixels = w * w
        val px = java.io.ByteArrayOutputStream()
        while (px.size() < pixels) {
            val len = gif[p++].toInt() and 0xff
            assertEquals(minOf(126, pixels - px.size()) + 1, len)
            assertEquals(0x80, gif[p++].toInt() and 0xff)
            px.write(gif, p, len - 1)
            p += len - 1
        }
        if (pixels % 126 == 0) {
            assertEquals(1, gif[p++].toInt())
            assertEquals(0x80, gif[p++].toInt() and 0xff)
        }
        assertEquals(0x01, gif[p++].toInt())
        assertEquals(0x81, gif[p++].toInt() and 0xff)
        assertEquals(0x00, gif[p++].toInt())
        assertEquals(0x3B, gif[p++].toInt())
        assertEquals(gif.size, p)
        return px.toByteArray()
    }

    private fun flat(m: Array<BooleanArray>): ByteArray = m.flatMap { r -> r.map { (if (it) 1 else 0).toByte() } }.toByteArray()

    @Test
    fun `gif structure, with and without the trailing clear code`() {
        val o1 = EncodeOptions()
        val w1 = QREncoder.encodeRaw("HELLO WORLD", o1)
        assertTrue(w1.size * w1.size % 126 != 0)
        assertArrayEquals(flat(w1), checkGifStructure(QREncoder.encodeGif("HELLO WORLD", o1), w1.size))
        val o2 = EncodeOptions(border = 21, scale = 2)
        val w2 = QREncoder.encodeRaw("HELLO WORLD", o2)
        assertEquals(126, w2.size)
        assertArrayEquals(flat(w2), checkGifStructure(QREncoder.encodeGif("HELLO WORLD", o2), w2.size))
    }

    @Test
    fun `ImageIO decodes the gif pixel-exact`() {
        for (o in listOf(EncodeOptions(), EncodeOptions(border = 21, scale = 2), EncodeOptions(scale = 3, border = 1))) {
            val raw = QREncoder.encodeRaw("HELLO WORLD", o)
            val img = ImageIO.read(ByteArrayInputStream(QREncoder.encodeGif("HELLO WORLD", o)))!!
            assertEquals(raw.size, img.width)
            assertEquals(raw.size, img.height)
            for (y in raw.indices) for (x in raw.indices) {
                val rgb = img.getRGB(x, y) and 0xffffff
                assertEquals(if (raw[y][x]) 0x000000 else 0xffffff, rgb, "$o ($x,$y)")
            }
        }
    }

    @Test
    fun `base64 matches java util Base64`() {
        val rnd = Random(42)
        for (n in 0..300) {
            val b = rnd.nextBytes(n)
            assertEquals(Base64.getEncoder().encodeToString(b), Renderers.base64(b), "len $n")
        }
    }

    // ---- gif.test.ts ports ----

    /** ImageIO-decoded GIF as a module matrix (black = true); fails on any non-black/white pixel. */
    private fun gifMatrix(gif: ByteArray): Array<BooleanArray> {
        val img = ImageIO.read(ByteArrayInputStream(gif))!!
        return Array(img.height) { y ->
            BooleanArray(img.width) { x ->
                when (val rgb = img.getRGB(x, y) and 0xffffff) {
                    0x000000 -> true
                    0xffffff -> false
                    else -> error("unexpected pixel ${rgb.toString(16)} at ($x,$y)")
                }
            }
        }
    }

    @Test
    fun `gif round-trips pixel-exact`() {
        // gif.test.ts L9-21
        for ((text, ecc) in listOf(
            "HELLO WORLD" to ErrorCorrection.MEDIUM,
            "H".repeat(768) to ErrorCorrection.MEDIUM,
            "x".repeat(1200) to ErrorCorrection.HIGH, // byte mode, high version
        )) {
            val o = EncodeOptions(ecc = ecc)
            assertTrue(QREncoder.encodeRaw(text, o).contentDeepEquals(gifMatrix(QREncoder.encodeGif(text, o))), "$text $ecc")
        }
    }

    @Test
    fun `gif decodes through QRDecoder via scale`() {
        // gif.test.ts L23-33: decode-side upscaling of the 1px-per-module gif, then encoder-side scale 4.
        val o = EncodeOptions(ecc = ErrorCorrection.MEDIUM)
        val m = gifMatrix(QREncoder.encodeGif("GIF PIPELINE", o))
        for (scale in listOf(2, 4)) assertEquals("GIF PIPELINE", QRDecoder.decode(TestImages.matrixToImage(m, scale)), "scale $scale")
        val big = gifMatrix(QREncoder.encodeGif("GIF PIPELINE", o.copy(scale = 4)))
        assertEquals("GIF PIPELINE", QRDecoder.decode(TestImages.matrixToImage(big, 1)))
    }

    @Test
    fun `data url body is the exact gif and decodes`() {
        // gif.test.ts L176-189
        val prefix = "data:image/gif;base64,"
        for ((text, scale) in listOf("GIF PIPELINE" to 4, "H".repeat(768) to 4)) {
            val o = EncodeOptions(ecc = ErrorCorrection.MEDIUM, scale = scale)
            val url = QREncoder.encodeDataUrl(text, o)
            assertTrue(url.startsWith(prefix))
            val bytes = Base64.getDecoder().decode(url.substring(prefix.length))
            assertArrayEquals(QREncoder.encodeGif(text, o), bytes)
            assertEquals(text, QRDecoder.decode(TestImages.matrixToImage(gifMatrix(bytes), 1)))
        }
    }

    @Test
    fun `term rows hold exactly W cells`() {
        // index.ts renderTerm L1001-1013
        val dark = "\u001b[40m  \u001b[0m"
        val light = "\u001b[1;47m  \u001b[0m"
        for (o in listOf(EncodeOptions(), EncodeOptions(scale = 2, border = 3))) {
            val raw = QREncoder.encodeRaw("HELLO WORLD", o)
            val term = QREncoder.encodeTerm("HELLO WORLD", o)
            assertTrue(term.endsWith("\n"))
            val rows = term.removeSuffix("\n").split("\n")
            assertEquals(raw.size, rows.size)
            for ((y, row) in rows.withIndex()) {
                assertEquals(raw[y].joinToString("") { if (it) dark else light }, row, "row $y")
            }
        }
    }
}
