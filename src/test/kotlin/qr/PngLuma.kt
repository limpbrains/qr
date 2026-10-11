package qr

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.File
import javax.imageio.ImageIO

/**
 * PNG luma matching qr-paulmillr test/misc/png.ts `decodePNGLuma` (v0.7.2) for the color types the
 * vectors use (palette 1..8-bit incl. tRNS, RGB8, RGBA8). Pixels come from ImageIO (no gamma is
 * applied by the JDK PNG reader; palette/sub-byte samples expand to exact RGB), then png.ts L253-260 /
 * L327-335 is replicated: alpha is composited onto white with `((c * al + 255 * (255 - al) + 127) / 255) | 0`
 * per channel, and luma = `(r + 2g + b) >>> 2`.
 *
 * Not covered (no such vectors): grayscale PNGs (png.ts uses the raw/expanded gray sample directly,
 * whereas getRGB on a gray BufferedImage goes through a colour-space conversion) and 16-bit PNGs
 * (png.ts keeps the high byte). Those are rejected rather than silently approximated.
 * RGB8+tRNS colour keys are composited by ImageIO, whereas png.ts applies tRNS only to palette images
 * (no vector is affected; the luma CRC guard covers the vectors).
 */
object PngLuma {
    fun decode(file: File): Luma {
        val img: BufferedImage = ImageIO.read(file) ?: throw IllegalArgumentException("not a PNG: $file")
        val cm = img.colorModel
        val supported = cm is IndexColorModel ||
            (cm.colorSpace.type == java.awt.color.ColorSpace.TYPE_RGB &&
                cm.componentSize.all { it == 8 })
        require(supported) { "PngLuma: unsupported PNG color model $cm in $file" }
        val w = img.width
        val h = img.height
        val argb = img.getRGB(0, 0, w, h, null, 0, w)
        val out = ByteArray(w * h)
        for (i in argb.indices) {
            val px = argb[i]
            val al = px ushr 24
            var r = (px shr 16) and 0xff
            var g = (px shr 8) and 0xff
            var bl = px and 0xff
            if (al != 255) {
                val wgt = 255 - al
                r = (r * al + 255 * wgt + 127) / 255
                g = (g * al + 255 * wgt + 127) / 255
                bl = (bl * al + 255 * wgt + 127) / 255
            }
            out[i] = ((r + 2 * g + bl) ushr 2).toByte()
        }
        return Luma(w, h, out)
    }
}
