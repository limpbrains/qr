package com.keklol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BitmapTest {

    @Test
    fun `constructor should create bitmap with correct dimensions`() {
        val bmp = Bitmap(10, 20)
        assertEquals(10, bmp.width)
        assertEquals(20, bmp.height)
    }

    @Test
    fun `single size constructor should create square bitmap`() {
        val bmp = Bitmap(15)
        assertEquals(15, bmp.width)
        assertEquals(15, bmp.height)
    }

    @Test
    fun `set and get should work correctly`() {
        val bmp = Bitmap(5, 5)
        assertNull(bmp.get(2, 2))

        bmp.set(2, 2, true)
        assertEquals(true, bmp.get(2, 2))

        bmp.set(2, 2, false)
        assertEquals(false, bmp.get(2, 2))
    }

    @Test
    fun `isInside should detect boundaries correctly`() {
        val bmp = Bitmap(10, 10)
        assertTrue(bmp.isInside(Point(0.0, 0.0)))
        assertTrue(bmp.isInside(Point(9.0, 9.0)))
        assertFalse(bmp.isInside(Point(-1.0, 0.0)))
        assertFalse(bmp.isInside(Point(0.0, -1.0)))
        assertFalse(bmp.isInside(Point(10.0, 0.0)))
        assertFalse(bmp.isInside(Point(0.0, 10.0)))
    }

    @Test
    fun `rect should fill rectangular region`() {
        val bmp = Bitmap(10, 10)
        bmp.rect(2, 2, 3, 3, true)

        for (y in 0 until 10) {
            for (x in 0 until 10) {
                val expected = x in 2..4 && y in 2..4
                assertEquals(expected, bmp.get(x, y) == true, "Failed at ($x, $y)")
            }
        }
    }

    @Test
    fun `hLine should draw horizontal line`() {
        val bmp = Bitmap(10, 10)
        bmp.hLine(2, 5, 4, true)

        for (x in 2..5) {
            assertEquals(true, bmp.get(x, 5))
        }
        assertNull(bmp.get(1, 5))
        assertNull(bmp.get(6, 5))
    }

    @Test
    fun `vLine should draw vertical line`() {
        val bmp = Bitmap(10, 10)
        bmp.vLine(5, 2, 4, true)

        for (y in 2..5) {
            assertEquals(true, bmp.get(5, y))
        }
        assertNull(bmp.get(5, 1))
        assertNull(bmp.get(5, 6))
    }

    @Test
    fun `border should add border around bitmap`() {
        val bmp = Bitmap(3, 3)
        bmp.rect(0, 0, 3, 3, true)

        val bordered = bmp.border(2, false)
        assertEquals(7, bordered.width)
        assertEquals(7, bordered.height)

        // Check border is false
        assertEquals(false, bordered.get(0, 0))
        assertEquals(false, bordered.get(6, 6))

        // Check center is preserved
        assertEquals(true, bordered.get(3, 3))
    }

    @Test
    fun `clone should create independent copy`() {
        val bmp = Bitmap(5, 5)
        bmp.set(2, 2, true)

        val clone = bmp.clone()
        clone.set(2, 2, false)

        assertEquals(true, bmp.get(2, 2))
        assertEquals(false, clone.get(2, 2))
    }

    @Test
    fun `toImage should convert to RGBA format`() {
        val bmp = Bitmap(2, 2)
        bmp.set(0, 0, true)  // black
        bmp.set(1, 1, true)  // black

        val img = bmp.toImage(isRGB = false)
        assertEquals(2, img.width)
        assertEquals(2, img.height)
        assertEquals(16, img.data.size)  // 2x2x4 (RGBA)

        // First pixel (0,0) is black
        assertEquals(0, img.data[0].toInt() and 0xFF)
        assertEquals(0, img.data[1].toInt() and 0xFF)
        assertEquals(0, img.data[2].toInt() and 0xFF)
        assertEquals(255.toByte(), img.data[3])  // alpha
    }

    @Test
    fun `toASCII should produce valid output`() {
        val bmp = Bitmap(4, 4)
        bmp.rect(0, 0, 4, 4, true)

        val ascii = bmp.toASCII()
        assertTrue(ascii.isNotEmpty())
    }

    @Test
    fun `fromString should parse correctly`() {
        val str = """
            X X
             X
            X X
        """.trimIndent()

        val bmp = Bitmap.fromString(str)
        assertEquals(3, bmp.width)
        assertEquals(3, bmp.height)

        assertEquals(true, bmp.get(0, 0))
        assertEquals(false, bmp.get(1, 0))
        assertEquals(true, bmp.get(2, 0))
        assertEquals(false, bmp.get(0, 1))
        assertEquals(true, bmp.get(1, 1))
    }
}
