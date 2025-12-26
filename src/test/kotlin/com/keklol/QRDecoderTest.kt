package com.keklol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class QRDecoderTest {

    @Test
    fun `decode should throw for empty image`() {
        assertThrows<IllegalArgumentException> {
            QRDecoder.decode(0, 0, byteArrayOf())
        }
    }

    @Test
    fun `decode should throw for too small image`() {
        // Create a very small image (below minimum QR code size)
        val width = 10
        val height = 10
        val data = ByteArray(width * height * 4) { 255.toByte() }

        assertThrows<ImageTooSmallException> {
            QRDecoder.decode(width, height, data)
        }
    }

    @Test
    fun `Image constructor should validate dimensions`() {
        assertThrows<IllegalArgumentException> {
            Image(-1, 10, ByteArray(100))
        }
        assertThrows<IllegalArgumentException> {
            Image(10, 0, ByteArray(100))
        }
        assertThrows<IllegalArgumentException> {
            Image(10, 10, byteArrayOf())
        }
    }

    @Test
    fun `Image bytesPerPixel should detect RGB and RGBA`() {
        val rgbImage = Image(10, 10, ByteArray(10 * 10 * 3))
        assertEquals(3, rgbImage.bytesPerPixel)

        val rgbaImage = Image(10, 10, ByteArray(10 * 10 * 4))
        assertEquals(4, rgbaImage.bytesPerPixel)
    }

    @Test
    fun `Image bytesPerPixel should throw for invalid format`() {
        val invalidImage = Image(10, 10, ByteArray(10 * 10 * 2))
        assertThrows<IllegalArgumentException> {
            invalidImage.bytesPerPixel
        }
    }

    // Helper to create a simple test QR code bitmap as an Image
    // This creates a minimal QR code pattern for testing
    private fun createTestQRImage(): Image {
        // This is a simplified version - real tests would use actual QR code images
        // For now, we test that the pipeline doesn't crash on valid input dimensions
        val size = 100
        val data = ByteArray(size * size * 4) { 255.toByte() }  // White background
        return Image(size, size, data)
    }

    @Test
    fun `decode should throw FinderNotFoundException for blank image`() {
        val image = createTestQRImage()
        assertThrows<FinderNotFoundException> {
            QRDecoder.decode(image)
        }
    }

    @Test
    fun `Point operations should work correctly`() {
        val p1 = Point(3.0, 4.0)
        val p2 = Point(1.0, 2.0)

        assertEquals(Point(4.0, 6.0), p1 + p2)
        assertEquals(Point(2.0, 2.0), p1 - p2)
        assertEquals(Point(-3.0, -4.0), -p1)
        assertEquals(Point(4.0, 3.0), p1.mirror())
    }

    @Test
    fun `Point distance should calculate correctly`() {
        val p1 = Point(0.0, 0.0)
        val p2 = Point(3.0, 4.0)

        assertEquals(5.0, Point.distance(p1, p2), 0.0001)
        assertEquals(25.0, Point.distance2(p1, p2), 0.0001)
    }

    @Test
    fun `Pattern merge should calculate weighted average`() {
        val p1 = Pattern(0.0, 0.0, 2.0, 1)
        val p2 = Pattern(4.0, 4.0, 2.0, 1)

        val merged = p1.merge(p2)
        assertEquals(2.0, merged.x, 0.0001)
        assertEquals(2.0, merged.y, 0.0001)
        assertEquals(2.0, merged.moduleSize, 0.0001)
        assertEquals(2, merged.count)
    }

    @Test
    fun `ErrorCorrection should have correct codes`() {
        assertEquals(0b01, ErrorCorrection.LOW.code)
        assertEquals(0b00, ErrorCorrection.MEDIUM.code)
        assertEquals(0b11, ErrorCorrection.QUARTILE.code)
        assertEquals(0b10, ErrorCorrection.HIGH.code)
    }

    @Test
    fun `ErrorCorrection fromCode should return correct enum`() {
        assertEquals(ErrorCorrection.LOW, ErrorCorrection.fromCode(0b01))
        assertEquals(ErrorCorrection.MEDIUM, ErrorCorrection.fromCode(0b00))
        assertEquals(ErrorCorrection.QUARTILE, ErrorCorrection.fromCode(0b11))
        assertEquals(ErrorCorrection.HIGH, ErrorCorrection.fromCode(0b10))
    }

    @Test
    fun `EncodingType should have correct mode bits`() {
        assertEquals("0001", EncodingType.NUMERIC.modeBits)
        assertEquals("0010", EncodingType.ALPHANUMERIC.modeBits)
        assertEquals("0100", EncodingType.BYTE.modeBits)
    }

    @Test
    fun `EncodingType fromModeBits should return correct enum`() {
        assertEquals(EncodingType.NUMERIC, EncodingType.fromModeBits("0001"))
        assertEquals(EncodingType.ALPHANUMERIC, EncodingType.fromModeBits("0010"))
        assertEquals(EncodingType.BYTE, EncodingType.fromModeBits("0100"))
        assertNull(EncodingType.fromModeBits("1111"))
    }
}
