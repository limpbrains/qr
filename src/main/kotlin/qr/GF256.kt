package qr

/**
 * JS-shaped GF(2^8) exp/log tables with the QR primitive polynomial 0x11d (index.ts L263-273).
 * [EXP] is doubled (`EXP[i + 255] == EXP[i]`) so the sum of two logs indexes it without a mod.
 */
object GF256 {
    @JvmField
    val EXP = IntArray(510)

    @JvmField
    val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            EXP[i + 255] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
    }

    /** Field product (decode.ts L130). */
    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Multiplicative inverse (decode.ts L131). Like JS, `inv(0)` returns 1 instead of throwing. */
    fun inv(a: Int): Int = EXP[255 - LOG[a]]
}
