package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.zip.CRC32

/**
 * Pyramid + binarizer parity with qr v0.7.2: per-layer CRC32 of luma, blocks, cuts and bitmap
 * against fixtures in src/test/resources/dumps/layers, produced by
 * `node --experimental-strip-types tools/js-dump/dump-layers.mjs ../qr-paulmillr <image>`.
 * Finder records and mandatory picks in the same fixtures are asserted by FinderDumpTest.
 */
class LayerDumpTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun layersMatchJs(name: String) {
        val dump = load(name)
        val img = image(dump)
        assertEquals((dump["width"] as Double).toInt(), img.width)
        assertEquals((dump["height"] as Double).toInt(), img.height)
        // maxSize above the image keeps stale/zero arena tails reachable by the OOB-prone reads.
        val scanner = QRScanner(maxWidth = (dump["maxWidth"] as Double).toInt(), maxHeight = (dump["maxHeight"] as Double).toInt())
        scanner.addImage(img)
        scanner.ensureResized()
        val expected = dump["layers"] as List<*>
        val used = scanner.layers.filter { it.used }
        assertEquals(expected.size, used.size, "used layer count")
        for (e in expected) {
            e as Map<*, *>
            val i = (e["index"] as Double).toInt()
            val layer = scanner.layers[i]
            scanner.prepareLayer(layer)
            fun num(key: String) = (e[key] as Double).toLong()
            val at = "layer $i"
            assertEquals(num("width"), layer.width.toLong(), "$at width")
            assertEquals(num("height"), layer.height.toLong(), "$at height")
            assertEquals(num("words"), layer.words.toLong(), "$at words")
            assertEquals(num("blockWidth"), layer.blockWidth.toLong(), "$at blockWidth")
            assertEquals(num("blockHeight"), layer.blockHeight.toLong(), "$at blockHeight")
            val nb = layer.blockWidth * layer.blockHeight
            assertEquals(num("lumaCrc"), crc(layer.luma, layer.width * layer.height), "$at lumaCrc")
            assertEquals(num("blocksCrc"), crc(ByteArray(nb) { layer.blocks[it].toByte() }, nb), "$at blocksCrc")
            val cuts = ByteArray(nb * 2)
            for (k in 0 until nb) {
                val v = layer.cuts[k]
                cuts[2 * k] = v.toByte()
                cuts[2 * k + 1] = (v shr 8).toByte()
            }
            assertEquals(num("cutsCrc"), crc(cuts, cuts.size), "$at cutsCrc")
            val n = layer.words * layer.height
            val bits = ByteArray(n * 4)
            for (k in 0 until n) {
                val v = layer.bitmap[k]
                for (b in 0 until 4) bits[4 * k + b] = (v shr (8 * b)).toByte()
            }
            assertEquals(num("bitmapCrc"), crc(bits, bits.size), "$at bitmapCrc")
            // Finder records and picks are asserted by FinderDumpTest.
            val patterns = e["patterns"] as List<*>
            assertTrue(patterns.size.toLong() == num("patternCount"), "$at fixture pattern list")
        }
    }


    private fun crc(bytes: ByteArray, len: Int): Long = CRC32().apply { update(bytes, 0, len) }.value


    companion object {
        /** Fixture names under src/test/resources/dumps/layers (also used by FinderDumpTest). */
        @JvmStatic
        fun fixtures() = listOf(
            "blurred-image003", "nominal-image001", "pathological-image005", "high_version-image031",
            "issues-invert", "rotations-image021", "lots-image001", "perspective-image010", "curved-image017",
            "brightness-image004",
            "synthetic-48x24", "synthetic-32x48", "synthetic-32x48-max44x60",
            "synthetic-6x5", "synthetic-6x5-max10x9",
        )

        fun load(name: String): Map<*, *> =
            MiniJson.parse(LayerDumpTest::class.java.getResource("/dumps/layers/$name.json")!!.readText()) as Map<*, *>

        fun image(dump: Map<*, *>): Image {
            val path = dump["image"] as String
            return if (path.endsWith(".pgm")) readPgm(File(path)) else TestImages.readLuma(path)
        }

        /** Binary 8-bit PGM (P5, maxval 255), as written by dump-layers.mjs --synthetic. */
        private fun readPgm(file: File): Image {
            val buf = file.readBytes()
            val tokens = ArrayList<String>()
            var i = 0
            while (tokens.size < 4) {
                while (buf[i].toInt().toChar().isWhitespace()) i++
                val sb = StringBuilder()
                while (!buf[i].toInt().toChar().isWhitespace()) sb.append(buf[i++].toInt().toChar())
                tokens.add(sb.toString())
            }
            i++
            check(tokens[0] == "P5" && tokens[3] == "255") { "expected binary 8-bit PGM" }
            val w = tokens[1].toInt()
            val h = tokens[2].toInt()
            return Image(w, h, buf.copyOfRange(i, i + w * h))
        }
    }
}

/** Minimal JSON reader for test fixtures: objects -> Map, arrays -> List, numbers -> Double. */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        check(p.i == text.length) { "trailing JSON at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) {
                        val k = value() as String
                        ws(); check(s[i++] == ':')
                        m[k] = value()
                        ws()
                        if (s[i++] == '}') return m
                    }
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) {
                        l.add(value())
                        ws()
                        if (s[i++] == ']') return l
                    }
                }
                '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (s[i] != '"') {
                        val ch = s[i++]
                        if (ch != '\\') {
                            sb.append(ch)
                            continue
                        }
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    i++
                    sb.toString()
                }
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> {
                    val start = i
                    while (i < s.length && (s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i].isDigit())) i++
                    check(i > start) { "unexpected '$c' at $start" }
                    s.substring(start, i).toDouble()
                }
            }
        }
    }
}
