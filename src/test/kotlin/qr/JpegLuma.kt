package qr

/** One byte per pixel luma raster. */
class Luma(val width: Int, val height: Int, val data: ByteArray)

/**
 * Bit-exact port of the luma path of qr-paulmillr test/misc/jpg.ts (`decodeJPEGLuma`, v0.7.2).
 *
 * JS arithmetic is replicated literally: `number` is Double; Float32Array slots (`aan`, `qs`, `ws`)
 * round through Float on store; Int16Array coefficient stores wrap via `toShort()`;
 * Uint8ClampedArray stores clamp to 0..255 with round-half-to-even ([clampedByte]).
 * Chroma IDCT and RGBA conversion are skipped (jpg.ts skips them too in luma mode).
 */
object JpegLuma {
    // jpg.ts L20-36
    private val ZIGZAG: IntArray = IntArray(64).also { z ->
        var x = 0
        var y = 0
        for (k in 0 until 64) {
            z[k] = 8 * y + x
            if (((x + y) and 1) != 0) {
                if (y == 7) x++ else if (x == 0) y++ else { x--; y++ }
            } else {
                if (x == 7) y++ else if (y == 0) x++ else { x++; y-- }
            }
        }
    }

    // jpg.ts L38
    private class Huff(val min: IntArray, val max: IntArray, val ptr: IntArray, val vals: IntArray)

    // jpg.ts L39-51
    private class Comp(val id: Int, val h: Int, val v: Int, val tq: Int) {
        var bw = 0
        var bh = 0
        var pw = 0
        var ph = 0
        var coeffs = ShortArray(0)
        var pred = 0
        var plane = ByteArray(0)
    }

    // jpg.ts L52
    private class ScanComp(val c: Comp, val dc: Huff?, val ac: Huff?)

    /** ToUint8Clamp: NaN -> 0, clamp to [0, 255], round half to even. */
    private fun clampedByte(d: Double): Byte {
        if (d.isNaN() || d <= 0.0) return 0
        if (d >= 255.0) return 255.toByte()
        return Math.rint(d).toInt().toByte()
    }

    /** jpg.ts L554-556 (`decodeJPEGLuma`) -> L64-508 (`decode` with luma = true). */
    fun decode(bytes: ByteArray): Luma = Decoder(bytes).run()

    private class Decoder(private val b: ByteArray) {
        // JS `b[i]`: undefined (out of range) behaves as 0 in the bitwise/arithmetic contexts used here.
        private fun u(i: Int): Int = if (i >= 0 && i < b.size) b[i].toInt() and 0xff else 0
        private fun inRange(i: Int) = i >= 0 && i < b.size

        private var p = 2
        private var w = 0
        private var h = 0
        private var progressive = false
        private val comps = ArrayList<Comp>()
        private var hmax = 1
        private var vmax = 1
        private var mcusX = 0
        private var mcusY = 0
        private var ri = 0
        private val qtabs = arrayOfNulls<IntArray>(16)
        private val dcTabs = arrayOfNulls<Huff>(16)
        private val acTabs = arrayOfNulls<Huff>(16)

        private var bitBuf = 0
        private var bitCnt = 0

        // jpg.ts L87-97
        private fun nextByte(): Int {
            if (!inRange(p)) return -1
            val v = u(p)
            if (v == 0xff) {
                if (!inRange(p + 1) || u(p + 1) != 0) return -1
                p += 2
                return 0xff
            }
            p++
            return v
        }

        // jpg.ts L98-105
        private fun readBit(): Int {
            if (bitCnt == 0) {
                val v = nextByte()
                bitBuf = if (v < 0) 0 else v
                bitCnt = 8
            }
            return (bitBuf ushr --bitCnt) and 1
        }

        // jpg.ts L106-114
        private fun receive(n: Int): Int {
            while (bitCnt < n) {
                val v = nextByte()
                bitBuf = (bitBuf shl 8) or (if (v < 0) 0 else v)
                bitCnt += 8
            }
            bitCnt -= n
            return (bitBuf ushr bitCnt) and ((1 shl n) - 1)
        }

        // jpg.ts L115-118
        private fun extend(n: Int): Int {
            val v = receive(n)
            return if (v < (1 shl (n - 1))) v - (1 shl n) + 1 else v
        }

        // jpg.ts L119-132
        private fun buildHuff(counts: IntArray, vals: IntArray): Huff {
            val min = IntArray(17)
            val max = IntArray(17) { -1 }
            val ptr = IntArray(17)
            var code = 0
            var k = 0
            for (len in 1..16) {
                val c = counts[len - 1]
                min[len] = code
                ptr[len] = k
                if (c > 0) max[len] = code + c - 1
                k += c
                code = (code + c) shl 1
            }
            return Huff(min, max, ptr, vals)
        }

        // jpg.ts L133-140 (an out-of-range symbol index is `undefined` in JS, which acts as 0)
        private fun huffDecode(t: Huff): Int {
            var code = 0
            for (len in 1..16) {
                code = (code shl 1) or readBit()
                if (code <= t.max[len]) {
                    val i = t.ptr[len] + code - t.min[len]
                    return if (i >= 0 && i < t.vals.size) t.vals[i] else 0
                }
            }
            throw IllegalArgumentException("JPG: invalid huffman code")
        }

        // jpg.ts L146-148
        private fun refine(co: ShortArray, i: Int, p1: Int) {
            if (readBit() != 0 && (co[i].toInt() and p1) == 0) {
                val cur = co[i].toInt()
                co[i] = (cur + (if (cur >= 0) p1 else -p1)).toShort()
            }
        }

        private var eobrun = 0
        private var ss = 0
        private var se = 0
        private var ah = 0
        private var al = 0

        // jpg.ts L149-233
        private fun block(s: ScanComp, off: Int) {
            val co = s.c.coeffs
            if (!progressive) {
                val t = huffDecode(s.dc!!)
                s.c.pred += if (t != 0) extend(t) else 0
                co[off] = s.c.pred.toShort()
                var k = 1
                while (k < 64) {
                    val rs = huffDecode(s.ac!!)
                    val r = rs shr 4
                    if ((rs and 15) == 0) {
                        if (r != 15) break
                        k += 16
                        continue
                    }
                    k += r
                    if (k > 63) break
                    co[off + ZIGZAG[k++]] = extend(rs and 15).toShort()
                }
            } else if (ss == 0) {
                if (ah == 0) {
                    val t = huffDecode(s.dc!!)
                    s.c.pred += if (t != 0) extend(t) shl al else 0
                    co[off] = s.c.pred.toShort()
                } else {
                    co[off] = (co[off].toInt() or (readBit() shl al)).toShort()
                }
            } else if (ah == 0) {
                if (eobrun > 0) {
                    eobrun--
                    return
                }
                var k = ss
                while (k <= se) {
                    val rs = huffDecode(s.ac!!)
                    val r = rs shr 4
                    if ((rs and 15) == 0) {
                        if (r < 15) {
                            eobrun = (1 shl r) - 1 + (if (r != 0) receive(r) else 0)
                            break
                        }
                        k += 16
                        continue
                    }
                    k += r
                    if (k > 63) break
                    co[off + ZIGZAG[k++]] = (extend(rs and 15) * (1 shl al)).toShort()
                }
            } else {
                val p1 = 1 shl al
                var k = ss
                if (eobrun == 0) {
                    while (k <= se) {
                        val rs = huffDecode(s.ac!!)
                        var r = rs shr 4
                        var v = 0
                        if ((rs and 15) == 0) {
                            if (r < 15) {
                                eobrun = (1 shl r) + (if (r != 0) receive(r) else 0)
                                break
                            }
                        } else {
                            v = if (readBit() != 0) p1 else -p1
                        }
                        while (k <= se) {
                            val i = off + ZIGZAG[k]
                            if (co[i].toInt() != 0) {
                                refine(co, i, p1)
                            } else if (r == 0) {
                                if (v != 0) co[i] = v.toShort()
                                k++
                                break
                            } else {
                                r--
                            }
                            k++
                        }
                    }
                }
                if (eobrun > 0) {
                    while (k <= se) {
                        val i = off + ZIGZAG[k]
                        if (co[i].toInt() != 0) refine(co, i, p1)
                        k++
                    }
                    eobrun--
                }
            }
        }

        // jpg.ts L142-263
        private fun decodeScan(scomps: List<ScanComp>, ss: Int, se: Int, ah: Int, al: Int) {
            this.ss = ss
            this.se = se
            this.ah = ah
            this.al = al
            bitCnt = 0
            eobrun = 0
            for (s in scomps) s.c.pred = 0
            val one = scomps.size == 1
            val total = if (one) scomps[0].c.bw * scomps[0].c.bh else mcusX * mcusY
            var mcu = 0
            while (mcu < total) {
                var n = minOf(if (ri != 0) ri else total, total - mcu)
                while (n > 0) {
                    if (one) {
                        val c = scomps[0].c
                        block(scomps[0], ((mcu / c.bw) * c.pw + (mcu % c.bw)) * 64)
                    } else {
                        val my = mcu / mcusX
                        val mx = mcu % mcusX
                        for (s in scomps)
                            for (v in 0 until s.c.v)
                                for (hh in 0 until s.c.h)
                                    block(s, ((my * s.c.v + v) * s.c.pw + mx * s.c.h + hh) * 64)
                    }
                    n--
                    mcu++
                }
                if (mcu < total) {
                    bitCnt = 0
                    if (inRange(p) && u(p) == 0xff && (u(p + 1) and 0xf8) == 0xd0) p += 2 else break
                    for (s in scomps) s.c.pred = 0
                    eobrun = 0
                }
            }
            bitCnt = 0
        }

        private fun ceilDiv(a: Int, b: Int): Int = Math.ceil(a.toDouble() / b).toInt()

        // jpg.ts L64-508 (luma = true, scale = 1, no output buffer)
        fun run(): Luma {
            if (u(0) != 0xff || u(1) != 0xd8) throw IllegalArgumentException("not a JPG")
            // jpg.ts L265-366: segment parser
            while (p < b.size) {
                if (u(p) != 0xff) {
                    p++
                    continue
                }
                val m = if (inRange(p + 1)) u(p + 1) else -1
                p += 2
                if (m == 0xd8 || m == 0x01 || (m in 0xd0..0xd7)) continue
                if (m == 0xd9) break
                val end = p + ((u(p) shl 8) or u(p + 1))
                var q = p + 2
                if (m == 0xdb) {
                    while (q < end) {
                        val pq = u(q) shr 4
                        val t = IntArray(64)
                        qtabs[u(q++) and 15] = t
                        for (i in 0 until 64) {
                            t[ZIGZAG[i]] = if (pq != 0) (u(q) shl 8) or u(q + 1) else u(q)
                            q += if (pq != 0) 2 else 1
                        }
                    }
                } else if (m == 0xc4) {
                    while (q < end) {
                        val tc = u(q) shr 4
                        val th = u(q++) and 15
                        var n = 0
                        for (i in 0 until 16) n += u(q + i)
                        val counts = IntArray(16) { u(q + it) }
                        val vals = IntArray(maxOf(0, minOf(n, b.size - (q + 16)))) { u(q + 16 + it) }
                        val huff = buildHuff(counts, vals)
                        if (tc != 0) acTabs[th] = huff else dcTabs[th] = huff
                        q += 16 + n
                    }
                } else if (m == 0xdd) {
                    ri = (u(q) shl 8) or u(q + 1)
                } else if (m == 0xc0 || m == 0xc1 || m == 0xc2) {
                    if (comps.isNotEmpty()) throw IllegalArgumentException("JPG: multiple frames")
                    progressive = m == 0xc2
                    if (u(q) != 8) throw IllegalArgumentException("JPG: only 8-bit precision supported")
                    h = (u(q + 1) shl 8) or u(q + 2)
                    w = (u(q + 3) shl 8) or u(q + 4)
                    val nc = u(q + 5)
                    if (w == 0 || h == 0 || w.toLong() * h > (1L shl 28))
                        throw IllegalArgumentException("JPG: invalid dimensions")
                    if (nc != 1 && nc != 3) throw IllegalArgumentException("JPG: unsupported component count $nc")
                    q += 6
                    for (i in 0 until nc) {
                        val ch = u(q + 1) shr 4
                        val cv = u(q + 1) and 15
                        if (ch == 0 || cv == 0 || ch > 4 || cv > 4) throw IllegalArgumentException("JPG: invalid sampling")
                        comps.add(Comp(u(q), ch, cv, u(q + 2)))
                        q += 3
                    }
                    for (c in comps) {
                        hmax = maxOf(hmax, c.h)
                        vmax = maxOf(vmax, c.v)
                    }
                    mcusX = ceilDiv(w, 8 * hmax)
                    mcusY = ceilDiv(h, 8 * vmax)
                    for (c in comps) {
                        c.bw = ceilDiv(ceilDiv(w * c.h, hmax), 8)
                        c.bh = ceilDiv(ceilDiv(h * c.v, vmax), 8)
                        c.pw = mcusX * c.h
                        c.ph = mcusY * c.v
                        c.coeffs = ShortArray(c.pw * c.ph * 64)
                    }
                } else if (m in 0xc3..0xcf && m != 0xc8 && m != 0xcc) {
                    throw IllegalArgumentException("JPG: unsupported codec (lossless/arithmetic/hierarchical)")
                } else if (m == 0xda) {
                    if (comps.isEmpty()) throw IllegalArgumentException("JPG: scan before frame")
                    val ns = u(q++)
                    val scomps = ArrayList<ScanComp>()
                    for (i in 0 until ns) {
                        val id = u(q)
                        val c = comps.firstOrNull { it.id == id } ?: throw IllegalArgumentException("JPG: unknown scan component")
                        scomps.add(ScanComp(c, dcTabs[u(q + 1) shr 4], acTabs[u(q + 1) and 15]))
                        q += 2
                    }
                    val ss = u(q)
                    val se = u(q + 1)
                    val ah = u(q + 2) shr 4
                    val al = u(q + 2) and 15
                    if (se > 63 || ss > se) throw IllegalArgumentException("JPG: invalid spectral range")
                    val needDC = ss == 0 && !(progressive && ah > 0)
                    val needAC = if (progressive) ss > 0 else true
                    for (s in scomps)
                        if ((needDC && s.dc == null) || (needAC && s.ac == null))
                            throw IllegalArgumentException("JPG: missing huffman table")
                    if (progressive && ss > 0 && ns != 1) throw IllegalArgumentException("JPG: invalid progressive scan")
                    p = end
                    decodeScan(scomps, if (progressive) ss else 0, if (progressive) se else 63, ah, al)
                    continue
                }
                p = end
            }
            if (comps.isEmpty()) throw IllegalArgumentException("JPG: no frame")
            val lumaData = ByteArray(w * h)
            val c = comps[0]
            val lumaDirect = c.h == hmax && c.v == vmax
            idct(c, lumaDirect, lumaData)
            // jpg.ts L499-507
            if (!lumaDirect) {
                var dst = 0
                for (y in 0 until h) {
                    val row = ((y * c.v) / vmax) * c.pw * 8
                    for (x in 0 until w) lumaData[dst++] = c.plane[row + (x * c.h) / hmax]
                }
            }
            return Luma(w, h, lumaData)
        }

        // jpg.ts L374-497, for component 0 only (L382-385 drops the rest in luma mode)
        private fun idct(c: Comp, lumaDirect: Boolean, lumaData: ByteArray) {
            val aan = FloatArray(8) { 1f }
            for (k in 1 until 8) aan[k] = (Math.cos((k * Math.PI) / 16) * Math.sqrt(2.0)).toFloat()
            val ws = FloatArray(64)
            val qt = qtabs[c.tq] ?: throw IllegalArgumentException("JPG: missing quant table")
            val qs = DoubleArray(64)
            for (i in 0 until 64)
                qs[i] = ((qt[i] * aan[i shr 3].toDouble() * aan[i and 7].toDouble()) / 8).toFloat().toDouble()
            val stride = if (lumaDirect) w else c.pw * 8
            val plane = if (lumaDirect) lumaData else ByteArray(stride * c.ph * 8)
            c.plane = plane
            val co = c.coeffs
            for (by in 0 until c.ph) {
                for (bx in 0 until c.pw) {
                    val off = (by * c.pw + bx) * 64
                    val rows = if (lumaDirect) minOf(8, h - by * 8) else 8
                    val cols = if (lumaDirect) minOf(8, w - bx * 8) else 8
                    if (rows <= 0 || cols <= 0) continue
                    var blockAC = 0
                    for (i in 0 until 8) {
                        val vertical = co[off + i + 8].toInt() or
                            co[off + i + 16].toInt() or
                            co[off + i + 24].toInt() or
                            co[off + i + 32].toInt() or
                            co[off + i + 40].toInt() or
                            co[off + i + 48].toInt() or
                            co[off + i + 56].toInt()
                        blockAC = blockAC or vertical or (if (i != 0) co[off + i].toInt() else 0)
                        if (vertical == 0) {
                            val dc = (co[off + i] * qs[i]).toFloat()
                            var r = 0
                            while (r < 64) { ws[i + r] = dc; r += 8 }
                            continue
                        }
                        val t10 = co[off + i] * qs[i] + co[off + i + 32] * qs[i + 32]
                        val t11 = co[off + i] * qs[i] - co[off + i + 32] * qs[i + 32]
                        val d2 = co[off + i + 16] * qs[i + 16]
                        val d6 = co[off + i + 48] * qs[i + 48]
                        val t13 = d2 + d6
                        val t12 = (d2 - d6) * 1.414213562 - t13
                        val e0 = t10 + t13
                        val e3 = t10 - t13
                        val e1 = t11 + t12
                        val e2 = t11 - t12
                        val d1 = co[off + i + 8] * qs[i + 8]
                        val d3 = co[off + i + 24] * qs[i + 24]
                        val d5 = co[off + i + 40] * qs[i + 40]
                        val d7 = co[off + i + 56] * qs[i + 56]
                        val z13 = d5 + d3
                        val z10 = d5 - d3
                        val z11 = d1 + d7
                        val z12 = d1 - d7
                        val o7 = z11 + z13
                        val z5 = (z10 + z12) * 1.847759065
                        val o6 = 1.414213562 * (z11 - z13)
                        val t6 = -2.61312593 * z10 + z5 - o7
                        val t5 = o6 - t6
                        val t4 = 1.0823922 * z12 - z5 + t5
                        ws[i] = (e0 + o7).toFloat()
                        ws[i + 56] = (e0 - o7).toFloat()
                        ws[i + 8] = (e1 + t6).toFloat()
                        ws[i + 48] = (e1 - t6).toFloat()
                        ws[i + 16] = (e2 + t5).toFloat()
                        ws[i + 40] = (e2 - t5).toFloat()
                        ws[i + 32] = (e3 + t4).toFloat()
                        ws[i + 24] = (e3 - t4).toFloat()
                    }
                    var out = by * 8 * stride + bx * 8
                    if (blockAC == 0) {
                        val value = clampedByte(128 + co[off] * qs[0])
                        for (y in 0 until rows) {
                            plane.fill(value, out, out + cols)
                            out += stride
                        }
                        continue
                    }
                    var r = 0
                    while (r < rows * 8) {
                        val w0 = ws[r].toDouble()
                        val w1 = ws[r + 1].toDouble()
                        val w2 = ws[r + 2].toDouble()
                        val w3 = ws[r + 3].toDouble()
                        val w4 = ws[r + 4].toDouble()
                        val w5 = ws[r + 5].toDouble()
                        val w6 = ws[r + 6].toDouble()
                        val w7 = ws[r + 7].toDouble()
                        val t10 = w0 + w4
                        val t11 = w0 - w4
                        val t13 = w2 + w6
                        val t12 = (w2 - w6) * 1.414213562 - t13
                        val e0 = t10 + t13
                        val e3 = t10 - t13
                        val e1 = t11 + t12
                        val e2 = t11 - t12
                        val z13 = w5 + w3
                        val z10 = w5 - w3
                        val z11 = w1 + w7
                        val z12 = w1 - w7
                        val o7 = z11 + z13
                        val z5 = (z10 + z12) * 1.847759065
                        val t6 = -2.61312593 * z10 + z5 - o7
                        val t5 = 1.414213562 * (z11 - z13) - t6
                        val t4 = 1.0823922 * z12 - z5 + t5
                        if (cols == 8) {
                            plane[out] = clampedByte(128 + e0 + o7)
                            plane[out + 7] = clampedByte(128 + e0 - o7)
                            plane[out + 1] = clampedByte(128 + e1 + t6)
                            plane[out + 6] = clampedByte(128 + e1 - t6)
                            plane[out + 2] = clampedByte(128 + e2 + t5)
                            plane[out + 5] = clampedByte(128 + e2 - t5)
                            plane[out + 4] = clampedByte(128 + e3 + t4)
                            plane[out + 3] = clampedByte(128 + e3 - t4)
                        } else {
                            plane[out] = clampedByte(128 + e0 + o7)
                            if (cols > 1) plane[out + 1] = clampedByte(128 + e1 + t6)
                            if (cols > 2) plane[out + 2] = clampedByte(128 + e2 + t5)
                            if (cols > 3) plane[out + 3] = clampedByte(128 + e3 - t4)
                            if (cols > 4) plane[out + 4] = clampedByte(128 + e3 + t4)
                            if (cols > 5) plane[out + 5] = clampedByte(128 + e2 - t5)
                            if (cols > 6) plane[out + 6] = clampedByte(128 + e1 - t6)
                        }
                        r += 8
                        out += stride
                    }
                }
            }
            c.coeffs = ShortArray(0)
        }
    }
}
