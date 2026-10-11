package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Single real-image cases from qr-paulmillr test/decode.test.ts (line numbers cited), run through
 * the [QRDecoder] facade (JS `decodeQR` defaults). JPEG luma comes from the bit-exact port of the JS
 * test reader ([TestImages.readLuma]); RGBA/RGB rasters come from ImageIO, which is exact for PNG
 * and close to (not bit-exact with) jpeg-js for JPEG.
 */
class RealImageTest {
    // L1800-1804
    @Test
    fun `gh-28 invert`() {
        assertEquals("https://patreon.com/reactiive", QRDecoder.decode(TestImages.readLuma("issues/invert.jpg")))
    }

    // L918-925
    @Test
    fun `gh-28 eci`() {
        assertEquals(
            "Latin1\t®ÄËÖ¶|\rCyrillic\tфДШлЮЯЩ\rGreek\tΣAβΔΦΩ\rThai\tโก๛ณ๗ฟ\rShiftJIS\t｢ﾓﾄｽｦﾊﾋﾌﾍﾎﾏ｣\r" +
                "Arabic\tلخأضخک\rUTF-8\t條碼字體\rBig5\t圖常用字次\rLatin1End",
            QRDecoder.decode(TestImages.readLuma("issues/eci.jpg")),
        )
    }

    // L1764-1793: default options, RGBA from the PNG.
    @Test
    fun `high-density misc image`() {
        assertEquals(ImageVectors.HIGH_DENSITY_EXPECTED, QRDecoder.decode(TestImages.readRGBA("misc/user-images-53473345.png")))
    }

    // L48-70: the (r + 2g + b) >> 2 luma plane decodes like the RGBA image.
    @Test
    fun `grayscale photo decodes like its RGBA image`() {
        val expected = "https://www.surveymonkey.com/s/TheClubatLAS_T3"
        val rgba = TestImages.readRGBA("boofcv-v3/detection/blurred/image007.jpg")
        val d = rgba.data
        val luma = ByteArray(rgba.width * rgba.height) { i ->
            val p = 4 * i
            (((d[p].toInt() and 0xFF) + 2 * (d[p + 1].toInt() and 0xFF) + (d[p + 2].toInt() and 0xFF)) ushr 2).toByte()
        }
        assertEquals(expected, QRDecoder.decode(rgba))
        assertEquals(expected, QRDecoder.decode(Image(rgba.width, rgba.height, luma)))
        assertEquals(expected, QRDecoder.decode(TestImages.readLuma("boofcv-v3/detection/blurred/image007.jpg")))
    }

    // L83-92
    @Test
    fun `real RGB image with an explicit format`() {
        val rgba = TestImages.readRGBA("boofcv-v3/detection/pathological/image005.png")
        val px = rgba.width * rgba.height
        val rgb = ByteArray(px * 3)
        for (i in 0 until px) System.arraycopy(rgba.data, 4 * i, rgb, 3 * i, 3)
        val s = QRScanner(rgba.width, rgba.height)
        s.addImage(rgba.width, rgba.height, rgb, PixelFormat.RGB)
        assertEquals(listOf("MAILTO:name@myemail.com"), s.decode())
        assertEquals("MAILTO:name@myemail.com", QRDecoder.decode(Image(rgba.width, rgba.height, rgb)))
    }

    // L1913-1916: the mirrored photo decodes to the same text (SyntheticTest covers the raster half).
    @Test
    fun `mirrored photo equals unmirrored`() {
        val photo = TestImages.readLuma("boofcv-v3/detection/blurred/image007.jpg")
        val expected = "https://www.surveymonkey.com/s/TheClubatLAS_T3"
        assertEquals(expected, QRDecoder.decode(photo))
        assertEquals(expected, QRDecoder.decode(TestImages.mirror(photo)))
    }
}
