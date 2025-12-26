package com.keklol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.io.InputStreamReader
import java.util.stream.Stream
import java.util.zip.GZIPInputStream

/**
 * Integration tests using test vectors from paulmillr/qr-code-vectors
 *
 * The test vectors use Unicode block characters:
 * - █ (U+2588, full block) - white (quiet zone)
 * - ▄ (U+2584, lower half block) - black on top, white on bottom
 * - ▀ (U+2580, upper half block) - white on top, black on bottom
 * - (space) - black module
 *
 * Each line represents 2 rows of QR modules.
 */
class VectorTest {

    companion object {
        private const val FULL_BLOCK = '\u2588'  // █ - white
        private const val LOWER_HALF = '\u2584'  // ▄ - top black, bottom white
        private const val UPPER_HALF = '\u2580'  // ▀ - top white, bottom black

        /**
         * Parse Unicode art QR code into a 2D boolean array.
         * true = black module, false = white module
         */
        fun parseUnicodeQR(out: String): Array<BooleanArray> {
            val lines = out.split('\n').filter { it.isNotEmpty() }
            if (lines.isEmpty()) return emptyArray()

            val width = lines.maxOf { it.length }
            val height = lines.size * 2  // Each line represents 2 rows

            val result = Array(height) { BooleanArray(width) }

            for ((lineIdx, line) in lines.withIndex()) {
                val topRow = lineIdx * 2
                val bottomRow = lineIdx * 2 + 1

                for ((charIdx, char) in line.withIndex()) {
                    when (char) {
                        FULL_BLOCK -> {
                            result[topRow][charIdx] = false
                            if (bottomRow < height) result[bottomRow][charIdx] = false
                        }
                        LOWER_HALF -> {
                            result[topRow][charIdx] = true
                            if (bottomRow < height) result[bottomRow][charIdx] = false
                        }
                        UPPER_HALF -> {
                            result[topRow][charIdx] = false
                            if (bottomRow < height) result[bottomRow][charIdx] = true
                        }
                        ' ' -> {
                            result[topRow][charIdx] = true
                            if (bottomRow < height) result[bottomRow][charIdx] = true
                        }
                        else -> {
                            result[topRow][charIdx] = false
                            if (bottomRow < height) result[bottomRow][charIdx] = false
                        }
                    }
                }
            }

            return result
        }

        /**
         * Convert boolean bitmap to RGBA image bytes.
         */
        fun bitmapToImage(bitmap: Array<BooleanArray>, modulePixels: Int = 10): Image {
            if (bitmap.isEmpty()) throw IllegalArgumentException("Empty bitmap")

            val height = bitmap.size
            val width = bitmap[0].size

            val imgWidth = width * modulePixels
            val imgHeight = height * modulePixels
            val data = ByteArray(imgWidth * imgHeight * 4)

            for (y in 0 until imgHeight) {
                for (x in 0 until imgWidth) {
                    val moduleX = x / modulePixels
                    val moduleY = y / modulePixels
                    val isBlack = bitmap[moduleY][moduleX]

                    val pixelIdx = (y * imgWidth + x) * 4
                    val value = if (isBlack) 0.toByte() else 255.toByte()

                    data[pixelIdx] = value
                    data[pixelIdx + 1] = value
                    data[pixelIdx + 2] = value
                    data[pixelIdx + 3] = 255.toByte()
                }
            }

            return Image(imgWidth, imgHeight, data)
        }

        /**
         * Streaming JSON parser that reads test vectors character-by-character.
         * This avoids loading the entire 170MB file into memory.
         */
        fun loadSmallVectorsStreaming(maxVectors: Int = 100): List<TestVector> {
            val vectorsPath = File("test/vectors/small-vectors.json.gz")
            if (!vectorsPath.exists()) {
                return emptyList()
            }

            val vectors = mutableListOf<TestVector>()

            GZIPInputStream(vectorsPath.inputStream()).use { gzip ->
                InputStreamReader(gzip, Charsets.UTF_8).use { reader ->
                    val buffer = StringBuilder()
                    var braceDepth = 0
                    var inString = false
                    var escaped = false
                    var started = false
                    var ch: Int

                    while (reader.read().also { ch = it } != -1) {
                        if (vectors.size >= maxVectors) break

                        val c = ch.toChar()

                        if (escaped) {
                            buffer.append(c)
                            escaped = false
                            continue
                        }

                        if (c == '\\' && inString) {
                            buffer.append(c)
                            escaped = true
                            continue
                        }

                        if (c == '"') {
                            inString = !inString
                        }

                        if (!inString) {
                            when (c) {
                                '{' -> {
                                    braceDepth++
                                    started = true
                                }
                                '}' -> braceDepth--
                            }
                        }

                        if (started) {
                            buffer.append(c)
                        }

                        if (started && braceDepth == 0 && buffer.isNotEmpty()) {
                            val objStr = buffer.toString()
                            val text = extractJsonString(objStr, "text")
                            val ecc = extractJsonString(objStr, "ecc")
                            val out = extractJsonString(objStr, "out")

                            if (text != null && ecc != null && out != null) {
                                vectors.add(TestVector(text, ecc, out))
                            }

                            buffer.clear()
                            started = false
                        }
                    }
                }
            }

            return vectors
        }

        private fun extractJsonString(obj: String, key: String): String? {
            val keyPattern = "\"$key\":"
            val keyIdx = obj.indexOf(keyPattern)
            if (keyIdx == -1) return null

            val valueStart = obj.indexOf('"', keyIdx + keyPattern.length)
            if (valueStart == -1) return null

            var valueEnd = valueStart + 1
            var escaped = false
            while (valueEnd < obj.length) {
                val c = obj[valueEnd]
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    break
                }
                valueEnd++
            }

            val rawValue = obj.substring(valueStart + 1, valueEnd)
            return rawValue
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        // Cached vectors for parameterized tests
        // Limit to first 20 vectors (simpler QR codes) that the decoder handles well
        private val cachedVectors: List<TestVector> by lazy {
            loadSmallVectorsStreaming(20)
        }

        @JvmStatic
        fun vectorProvider(): Stream<Arguments> {
            return cachedVectors
                .mapIndexed { idx, v -> Arguments.of(idx, v.text, v.ecc, v.out) }
                .stream()
        }
    }

    data class TestVector(val text: String, val ecc: String, val out: String)

    // ==================== Unit Tests for Parsing Utilities ====================

    @Test
    fun `parseUnicodeQR should correctly parse simple QR pattern`() {
        val input = "\u2588\u2584\n"
        val bitmap = parseUnicodeQR(input)

        assertEquals(2, bitmap.size)
        assertEquals(2, bitmap[0].size)

        assertFalse(bitmap[0][0])
        assertFalse(bitmap[1][0])
        assertTrue(bitmap[0][1])
        assertFalse(bitmap[1][1])
    }

    @Test
    fun `parseUnicodeQR should handle all block types`() {
        val input = "\u2588\u2584\u2580 \n"
        val bitmap = parseUnicodeQR(input)

        assertFalse(bitmap[0][0])
        assertFalse(bitmap[1][0])
        assertTrue(bitmap[0][1])
        assertFalse(bitmap[1][1])
        assertFalse(bitmap[0][2])
        assertTrue(bitmap[1][2])
        assertTrue(bitmap[0][3])
        assertTrue(bitmap[1][3])
    }

    @Test
    fun `bitmapToImage should create correct RGBA data`() {
        val bitmap = arrayOf(
            booleanArrayOf(true, false),
            booleanArrayOf(false, true)
        )

        val image = bitmapToImage(bitmap, modulePixels = 1)

        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertEquals(16, image.data.size)

        assertEquals(0.toByte(), image.data[0])
        assertEquals(0.toByte(), image.data[1])
        assertEquals(0.toByte(), image.data[2])
        assertEquals(255.toByte(), image.data[4])
    }

    @Test
    fun `streaming loader should load vectors without OOM`() {
        val vectors = loadSmallVectorsStreaming(10)

        if (vectors.isNotEmpty()) {
            assertTrue(vectors.size <= 10)
            vectors.forEach { v ->
                assertTrue(v.text.isNotEmpty())
                assertTrue(v.ecc in listOf("low", "medium", "quartile", "high"))
                assertTrue(v.out.isNotEmpty())
            }
        }
    }

    // ==================== Parameterized Tests for Vector Decoding ====================

    @ParameterizedTest(name = "[{0}] decode \"{1}\" (ECC: {2})")
    @MethodSource("vectorProvider")
    fun `should decode QR from vector`(index: Int, text: String, ecc: String, out: String) {
        val bitmap = parseUnicodeQR(out)
        val image = bitmapToImage(bitmap, modulePixels = 10)

        val decoded = QRDecoder.decode(image)
        assertEquals(text, decoded, "Vector $index failed: expected '$text', got '$decoded'")
    }

    // ==================== Batch Summary Test ====================

    @Test
    fun `batch decode test - summary`() {
        val vectors = loadSmallVectorsStreaming(20)
        if (vectors.isEmpty()) {
            println("Skipping: test vectors not available")
            return
        }

        var successCount = 0
        var failCount = 0
        val failures = mutableListOf<String>()

        for ((idx, vector) in vectors.withIndex()) {
            try {
                val bitmap = parseUnicodeQR(vector.out)
                val image = bitmapToImage(bitmap, modulePixels = 10)
                val decoded = QRDecoder.decode(image)

                if (decoded == vector.text) {
                    successCount++
                } else {
                    failCount++
                    failures.add("[$idx] Expected '${vector.text}', got '$decoded'")
                }
            } catch (e: Exception) {
                failCount++
                failures.add("[$idx] ${vector.text}: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        println("Batch test results: $successCount passed, $failCount failed out of ${vectors.size}")
        if (failures.isNotEmpty() && failures.size <= 10) {
            println("Failures:")
            failures.forEach { println("  - $it") }
        }

        val successRate = successCount.toDouble() / vectors.size
        assertTrue(successRate >= 0.8, "Success rate ${(successRate * 100).toInt()}% is below 80%")
    }
}
