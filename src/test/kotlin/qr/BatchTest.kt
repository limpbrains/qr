package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `QRDecoder.decodeBatch` (decode.ts L3152-3175; decode-batch.test.ts). */
class BatchTest {
    private fun BatchResult.flat(): List<String> = texts + listOfNotNull(failure?.tag ?: error?.message)

    private fun symbol(text: String, scale: Int) = TestImages.matrixToImage(TestEncoder.encodeRaw(text, border = 4), scale)

    // decode-batch.test.ts L85-108
    @Test
    fun `positional results continue after misses`() {
        val first = symbol("BATCH FIRST", 4)
        val last = symbol("BATCH LAST", 5)
        val blank = Image(first.width, first.height, ByteArray(first.data.size) { 255.toByte() })
        val results = QRDecoder.decodeBatch(listOf(first, blank, last), maxWidth = 256, maxHeight = 256)
        assertEquals(
            listOf(listOf("BATCH FIRST", "finder"), listOf("finder"), listOf("BATCH LAST", "finder")),
            results.map { it.flat() },
        )
        assertEquals(BatchResult(listOf("BATCH FIRST"), Fail.FINDER), results[0])
    }

    // decode-batch.test.ts L189-200
    @Test
    fun `every QR in an image and a blank image with default capacity`() {
        val texts = listOf("MULTI ONE", "MULTI TWO", "MULTI ONE")
        val symbols = texts.map { symbol(it, 4) }
        val gap = 32
        val width = symbols.sumOf { it.width } + gap * (symbols.size - 1)
        val height = symbols[0].height
        val data = ByteArray(width * height * 4) { 255.toByte() }
        var offset = 0
        for (s in symbols) {
            for (y in 0 until height)
                System.arraycopy(s.data, y * s.width * 4, data, (y * width + offset) * 4, s.width * 4)
            offset += s.width + gap
        }
        val combined = Image(width, height, data)
        assertEquals(
            listOf(texts + "finder"),
            QRDecoder.decodeBatch(listOf(combined), maxWidth = width, maxHeight = height).map { it.flat() },
        )
        val blank = Image(width, height, ByteArray(data.size) { 255.toByte() })
        assertEquals(listOf(listOf("finder")), QRDecoder.decodeBatch(listOf(blank)).map { it.flat() })
    }

    @Test
    fun `an invalid image is isolated from its neighbours`() {
        val first = symbol("BATCH FIRST", 4)
        val last = symbol("BATCH LAST", 4)
        val big = symbol("TOO BIG", 10)
        assertTrue(big.width > 256)
        val results = QRDecoder.decodeBatch(listOf(first, big, last), maxWidth = 256, maxHeight = 256)
        assertEquals(listOf("BATCH FIRST", "finder"), results[0].flat())
        assertEquals(emptyList<String>(), results[1].texts)
        assertEquals(null, results[1].failure)
        assertTrue(results[1].error is IllegalArgumentException)
        assertEquals(listOf("BATCH LAST", "finder"), results[2].flat())
    }

    @Test
    fun `empty input yields an empty list`() {
        assertEquals(emptyList<BatchResult>(), QRDecoder.decodeBatch(emptyList()))
    }
}
