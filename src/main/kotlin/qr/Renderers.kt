package qr

/**
 * Rendering context (index.ts Raster L924-927): the finished symbol [m], output side [w] (border and
 * scale applied) and [map] from an output coordinate to its module index (-1 in the border).
 */
internal class Raster(val m: Array<BooleanArray>, val w: Int, val map: IntArray)

/** One function per output format (index.ts render* L939-1160). */
internal object Renderers {
    // index.ts renderRaw L939-958
    fun raw(r: Raster): Array<BooleanArray> {
        val m = r.m
        val w = r.w
        val map = r.map
        return Array(w) { y ->
            val my = map[y]
            val row = BooleanArray(w)
            if (my >= 0) {
                val src = m[my]
                for (x in 0 until w) {
                    val mx = map[x]
                    row[x] = mx >= 0 && src[mx]
                }
            }
            row
        }
    }

    // index.ts GLYPH L962: half-block glyphs by (upper, lower) darkness.
    private const val GLYPH = "█▀▄ "

    // index.ts renderAscii L970-999 (QUAD batching is perf-only; glyphs are appended one by one).
    fun ascii(r: Raster): String {
        val m = r.m
        val w = r.w
        val map = r.map
        val out = StringBuilder(((w + 1) / 2) * (w + 1))
        var y = 0
        while (y < w) {
            val my0 = map[y]
            val my1 = if (y + 1 < w) map[y + 1] else -2 // past the bottom edge reads dark
            for (x in 0 until w) {
                val mx = map[x]
                var g = if (my1 == -2) 1 else 0
                if (mx >= 0) {
                    if (my0 >= 0 && m[my0][mx]) g = g or 2
                    if (my1 >= 0 && m[my1][mx]) g = g or 1
                }
                out.append(GLYPH[g])
            }
            out.append('\n')
            y += 2
        }
        return out.toString()
    }

    // index.ts renderTerm L1001-1013
    fun term(r: Raster): String {
        val w = r.w
        val map = r.map
        val darkCell = "\u001b[40m  \u001b[0m"
        val lightCell = "\u001b[1;47m  \u001b[0m"
        val out = StringBuilder(w * (w * lightCell.length + 1))
        for (y in 0 until w) {
            val my = map[y]
            for (x in 0 until w) {
                val mx = map[x]
                out.append(if (mx >= 0 && my >= 0 && r.m[my][mx]) darkCell else lightCell)
            }
            out.append('\n')
        }
        return out.toString()
    }

    // index.ts digits/chars L1015-1019: character counts of a coordinate and of a signed offset.
    private fun digits(n: Int): Int = n.toString().length
    private fun chars(d: Int): Int = if (d < 0) 1 + digits(-d) else digits(d)

    // index.ts renderSvg L1028-1071 (the `cmds` cache is perf-only and byte-identical to the general rule).
    fun svg(r: Raster, optimize: Boolean): String {
        val m = r.m
        val w = r.w
        val map = r.map
        val out = StringBuilder()
        out.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(w).append("\" xmlns=\"http://www.w3.org/2000/svg\">")
        val pathData = StringBuilder()
        var prevX = 0
        var prevY = 0
        var hasPrev = false
        for (y in 0 until w) {
            val my = map[y]
            if (my < 0) continue
            val row = m[my]
            for (x in 0 until w) {
                val mx = map[x]
                if (mx < 0 || !row[mx]) continue
                if (!optimize) {
                    out.append("<rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"1\" height=\"1\" />")
                    continue
                }
                // The shorter move wins, relative on ties.
                val dx = x - prevX
                val dy = y - prevY
                if (hasPrev && chars(dx) + chars(dy) <= digits(x) + digits(y)) pathData.append('m').append(dx).append(' ').append(dy)
                else pathData.append('M').append(x).append(' ').append(y)
                pathData.append("h1v1")
                if (x < 10) pathData.append('H').append(x) else pathData.append("h-1")
                pathData.append('Z')
                prevX = x
                prevY = y
                hasPrev = true
            }
        }
        if (optimize) out.append("<path d=\"").append(pathData).append("\"/>")
        return out.append("</svg>").toString()
    }

    // index.ts renderGif L1073-1141: GIF87a, 128-color palette (0 = white, 1 = black), 8-bit LZW
    // codes with a clear code every 126 pixels (so no code-size growth). Written sequentially; the
    // JS copyWithin spreading is perf-only.
    fun gif(r: Raster): ByteArray {
        val w = r.w
        val map = r.map
        val pixels = w * w
        val n = 126 // pixels per LZW chunk, 8-bit codes until the next clear
        val fullChunks = pixels / n
        val tail = pixels % n
        val out = ByteArray(408 + fullChunks * (n + 2) + 2 + tail + 4)
        var p = 0
        fun u16(v: Int) {
            out[p++] = (v and 0xff).toByte()
            out[p++] = (v ushr 8).toByte()
        }
        for (b in intArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)) out[p++] = b.toByte() // 'GIF87a'
        u16(w)
        u16(w)
        out[p++] = 0xf6.toByte()
        p += 2
        out[p++] = 0xff.toByte()
        out[p++] = 0xff.toByte()
        out[p++] = 0xff.toByte()
        p += 3 * 127 // palette: 0 = white, 1 = black
        out[p++] = 0x2c
        p += 4
        u16(w)
        u16(w)
        out[p++] = 0x00
        out[p++] = 0x07
        var left = 0 // pixels left in the current chunk
        for (y in 0 until w) {
            val my = map[y]
            val row = if (my >= 0) r.m[my] else null
            for (x in 0 until w) {
                if (left == 0) {
                    val k = minOf(n, pixels - (y * w + x))
                    out[p++] = (k + 1).toByte()
                    out[p++] = 0x80.toByte() // LZW clear code
                    left = k
                }
                val mx = map[x]
                out[p++] = if (row != null && mx >= 0 && row[mx]) 1 else 0
                left--
            }
        }
        if (tail == 0) {
            out[p++] = 1
            out[p++] = 0x80.toByte()
        }
        out[p++] = 0x01
        out[p++] = 0x81.toByte() // end of information
        out[p++] = 0x00
        out[p++] = 0x3b
        check(p == out.size)
        return out
    }

    // index.ts gifDataUrl L1146-1156
    fun dataUrl(gif: ByteArray): String = "data:image/gif;base64," + base64(gif)

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Standard base64 with `=` padding and no line breaks (java.util.Base64 needs Android API 26). */
    fun base64(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val v = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8) or (bytes[i + 2].toInt() and 0xff)
            out.append(B64[v ushr 18]).append(B64[v ushr 12 and 63]).append(B64[v ushr 6 and 63]).append(B64[v and 63])
            i += 3
        }
        val rest = bytes.size - i
        if (rest > 0) {
            val v = (bytes[i].toInt() and 0xff shl 16) or (if (rest == 2) bytes[i + 1].toInt() and 0xff shl 8 else 0)
            out.append(B64[v ushr 18]).append(B64[v ushr 12 and 63])
            out.append(if (rest == 2) B64[v ushr 6 and 63] else '=').append('=')
        }
        return out.toString()
    }
}
