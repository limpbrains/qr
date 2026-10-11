package qr

import kotlin.test.Test
import kotlin.test.assertEquals

/** Sanity checks that tools/extract_decoded.py captured the whole JS table (counts computed in node). */
class ImageVectorsTest {
    @Test
    fun decodedTableShape() {
        assertEquals(16, ImageVectors.DECODED.size, "categories")
        assertEquals(326, ImageVectors.DECODED.values.sumOf { it.size }, "image entries")
    }

    @Test
    fun allOnlyShape() {
        assertEquals(15, ImageVectors.DECODED_ALL_ONLY.size)
    }

    @Test
    fun highDensityExpectedLength() {
        assertEquals(2164, ImageVectors.HIGH_DENSITY_EXPECTED.length)
    }
}
