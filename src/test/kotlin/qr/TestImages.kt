package qr

import java.io.File
import javax.imageio.ImageIO

/** Test-side image helpers mirroring qr-paulmillr test/utils.ts. */
object TestImages {
    /** qr/test/vectors (Gradle runs tests with the project dir as working dir, as VectorTest relies on). */
    val vectorsRoot: File = File("test/vectors")

    private val DETECTION = "boofcv-v3/detection"

    /**
     * utils.ts `readLuma`: JPEG -> jpg.ts `decodeJPEGLuma` port, PNG -> png.ts `decodePNGLuma` equivalent.
     * `relPath` is relative to [vectorsRoot], e.g. "boofcv-v3/detection/blurred/image003.jpg".
     */
    fun readLuma(relPath: String): Image {
        val file = File(vectorsRoot, relPath)
        val lower = relPath.lowercase()
        val luma = when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> JpegLuma.decode(file.readBytes())
            lower.endsWith(".png") -> PngLuma.decode(file)
            else -> throw IllegalArgumentException("unsupported image vector: $relPath")
        }
        return Image(luma.width, luma.height, luma.data)
    }

    /** RGBA (4 bpp) via ImageIO, for JS `readImage` call sites. Not bit-exact with jpeg-js. */
    fun readRGBA(relPath: String): Image {
        val file = File(vectorsRoot, relPath)
        val img = ImageIO.read(file) ?: throw IllegalArgumentException("unreadable image: $relPath")
        val w = img.width
        val h = img.height
        val argb = img.getRGB(0, 0, w, h, null, 0, w)
        val data = ByteArray(w * h * 4)
        for (i in argb.indices) {
            val px = argb[i]
            data[i * 4] = (px shr 16).toByte()
            data[i * 4 + 1] = (px shr 8).toByte()
            data[i * 4 + 2] = px.toByte()
            data[i * 4 + 3] = (px ushr 24).toByte()
        }
        return Image(w, h, data)
    }

    /** utils.ts L210-212. */
    fun isDecodeImage(name: String): Boolean = Regex("\\.(?:jpe?g|png)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

    fun listCategories(): List<String> =
        (File(vectorsRoot, DETECTION).listFiles() ?: emptyArray()).filter { it.isDirectory }.map { it.name }.sorted()

    fun listImages(category: String): List<String> =
        (File(vectorsRoot, "$DETECTION/$category").listFiles() ?: emptyArray())
            .filter { it.isFile && isDecodeImage(it.name) }.map { it.name }.sorted()

    /** decode.test.ts L1901-1911: left-right mirror, any bytes-per-pixel. */
    fun mirror(img: Image): Image {
        val bpp = img.data.size / (img.width * img.height)
        val data = ByteArray(img.data.size)
        for (y in 0 until img.height)
            for (x in 0 until img.width)
                for (c in 0 until bpp)
                    data[(y * img.width + x) * bpp + c] = img.data[(y * img.width + img.width - 1 - x) * bpp + c]
        return Image(img.width, img.height, data)
    }

    /**
     * utils.ts L280-304: nearest-neighbour upscale of a module matrix; dark (true) -> 0, light -> 255.
     * `rgb = true` -> 3 bpp, otherwise RGBA (4 bpp, alpha 255).
     */
    fun matrixToImage(matrix: Array<BooleanArray>, scale: Int = 1, rgb: Boolean = false): Image =
        render(matrix, scale, if (rgb) 3 else 4)

    /** Grayscale (1 bpp) variant of [matrixToImage]. */
    fun matrixToGrayImage(matrix: Array<BooleanArray>, scale: Int = 1): Image = render(matrix, scale, 1)

    private fun render(matrix: Array<BooleanArray>, scale: Int, bpp: Int): Image {
        val w = matrix.size * scale
        val data = ByteArray(w * w * bpp)
        for (y in 0 until w) {
            val row = matrix[y / scale]
            for (x in 0 until w) {
                val v = (if (row[x / scale]) 0 else 255).toByte()
                val pos = (y * w + x) * bpp
                for (c in 0 until minOf(bpp, 3)) data[pos + c] = v
                if (bpp == 4) data[pos + 3] = 255.toByte()
            }
        }
        return Image(w, w, data)
    }
}
