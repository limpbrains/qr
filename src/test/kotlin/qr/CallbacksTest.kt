package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `imageOnResult` / `imageOnBitmap` debug callbacks (decode.ts L155-168, L2849-2852, L3054-3061). */
class CallbacksTest {
    private fun isBinaryRgba(img: Image): Boolean {
        val d = img.data
        for (i in d.indices step 4) {
            val c = d[i].toInt() and 0xFF
            if (c != 0 && c != 255) return false
            if ((d[i + 1].toInt() and 0xFF) != c || (d[i + 2].toInt() and 0xFF) != c) return false
            if ((d[i + 3].toInt() and 0xFF) != 255) return false
        }
        return true
    }

    // decode.test.ts L400-414
    @Test
    fun `imageOnResult fires a 1px-per-module RGBA image on success`() {
        val raw = TestEncoder.encodeRaw("result image", version = 3, border = 4)
        val img = TestImages.matrixToImage(raw, 4)
        val results = ArrayList<Image>()
        val text = QRDecoder.decode(img, DecodeOptions(imageOnResult = { results.add(it) }))
        assertEquals("result image", text)
        assertEquals(1, results.size)
        val result = results[0]
        // imageOnResult reports the bare symbol grid (no quiet-zone border): v3 = 29.
        val symbolSize = 17 + 4 * 3
        assertEquals(listOf(symbolSize, symbolSize), listOf(result.width, result.height))
        assertEquals(symbolSize * symbolSize * 4, result.data.size)
        assertTrue(isBinaryRgba(result))
        // The sampled grid is the raw (masked) symbol: dark modules -> 0, light -> 255.
        for (y in 0 until symbolSize)
            for (x in 0 until symbolSize)
                assertEquals(if (raw[y + 4][x + 4]) 0 else 255, result.data[4 * (y * symbolSize + x)].toInt() and 0xFF)
    }

    @Test
    fun `imageOnResult does not fire when decoding fails`() {
        var calls = 0
        val blank = Image(64, 64, ByteArray(64 * 64 * 4) { 255.toByte() })
        assertThrows<FinderNotFoundException> {
            QRDecoder.decode(blank, DecodeOptions(imageOnResult = { calls++ }))
        }
        assertEquals(0, calls)
    }

    @Test
    fun `imageOnBitmap fires once per prepared layer with its binarized plane`() {
        val raw = TestEncoder.encodeRaw("BITMAP", version = 2, border = 4)
        val img = TestImages.matrixToImage(raw, 4)
        assertEquals(132, img.width)
        val images = ArrayList<Image>()
        val s = QRScanner(img.width, img.height, DecodeOptions(imageOnBitmap = { images.add(it) }))
        s.addImage(img)
        // all = true scans until failure, so every used & search layer gets thresholded.
        assertEquals(listOf("BITMAP"), s.decode(all = true))
        val searched = s.layers.filter { it.used && it.search }
        assertEquals(2, searched.size)
        assertEquals(searched.size, images.size)
        assertEquals(searched.size, s.layers.count { it.found })
        // Coarse-to-fine order: half layer first, then native.
        assertEquals(listOf(66, 132), images.map { it.width })
        for ((layer, bitmap) in searched.reversed().zip(images)) {
            assertEquals(layer.width, bitmap.width)
            assertEquals(layer.height, bitmap.height)
            assertEquals(layer.width * layer.height * 4, bitmap.data.size)
            assertTrue(isBinaryRgba(bitmap))
            for ((x, y) in listOf(0 to 0, 17 to 17, 20 to 40, layer.width / 2 to layer.height / 2, layer.width - 1 to layer.height - 1))
                assertEquals(if (layer.bit(x, y) != 0) 0 else 255, bitmap.data[4 * (y * layer.width + x)].toInt() and 0xFF)
        }
        val native = s.layers[0]
        var dark = 0
        for (y in 0 until native.height)
            for (x in 0 until native.width) {
                assertEquals(if (native.bit(x, y) != 0) 0 else 255, images[1].data[4 * (y * native.width + x)].toInt() and 0xFF)
                if (native.bit(x, y) != 0) dark++
            }
        assertTrue(dark > 0)
        // Layers are thresholded once per staged image.
        s.decode()
        assertEquals(2, images.size)
        s.addImage(img)
        s.decode()
        assertEquals(3, images.size)
    }

    @Test
    fun `a throwing callback propagates and leaves the scanner usable`() {
        val img = TestImages.matrixToImage(TestEncoder.encodeRaw("THROW", version = 2, border = 4), 4)
        var fail = true
        val s = QRScanner(img.width, img.height, DecodeOptions(imageOnResult = { if (fail) throw RuntimeException("boom") }))
        s.addImage(img)
        assertEquals("boom", assertThrows<RuntimeException> { s.decode() }.message)
        fail = false
        s.addImage(img)
        assertEquals(listOf("THROW"), s.decode())

        val second = TestImages.matrixToImage(TestEncoder.encodeRaw("NEXT", version = 2, border = 4), 4)
        var calls = 0
        val batch = QRDecoder.decodeBatch(
            listOf(img, second),
            DecodeOptions(pointsOnDetect = { _, _ -> if (calls++ == 0) throw RuntimeException("points") }),
            maxWidth = 256, maxHeight = 256,
        )
        assertEquals(2, batch.size)
        assertTrue(batch[0].error is RuntimeException)
        assertEquals("points", batch[0].error?.message)
        assertEquals(listOf("NEXT"), batch[1].texts)
        assertEquals(null, batch[1].error)
    }
}
