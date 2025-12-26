package qr

/**
 * Interleaves and de-interleaves data blocks for QR code error correction.
 */
class Interleave(private val version: Int, private val ecc: ErrorCorrection) {

    private val capacity = QRInfo.capacity(version, ecc)
    private val rs = ReedSolomon(capacity.words)

    /**
     * Interleave bytes from multiple blocks.
     * [[1, 2, 3], [4, 5, 6]] -> [1, 4, 2, 5, 3, 6]
     */
    private fun interleaveBytes(blocks: List<ByteArray>): ByteArray {
        var maxLen = 0
        var totalLen = 0
        for (block in blocks) {
            maxLen = maxOf(maxLen, block.size)
            totalLen += block.size
        }

        val result = ByteArray(totalLen)
        var idx = 0
        for (i in 0 until maxLen) {
            for (block in blocks) {
                if (i < block.size) {
                    result[idx++] = block[i]
                }
            }
        }
        return result
    }

    /**
     * Encode data by adding error correction and interleaving.
     */
    fun encode(bytes: ByteArray): ByteArray {
        var data = bytes
        val blocks = mutableListOf<ByteArray>()
        val eccBlocks = mutableListOf<ByteArray>()

        for (i in 0 until capacity.numBlocks) {
            val isShort = i < capacity.shortBlocks
            val len = capacity.blockLen + (if (isShort) 0 else 1)
            val block = data.copyOfRange(0, len)
            blocks.add(block)
            eccBlocks.add(rs.encode(block))
            data = data.copyOfRange(len, data.size)
        }

        val resBlocks = interleaveBytes(blocks)
        val resECC = interleaveBytes(eccBlocks)
        val res = ByteArray(resBlocks.size + resECC.size)
        resBlocks.copyInto(res)
        resECC.copyInto(res, resBlocks.size)
        return res
    }

    /**
     * Decode data by de-interleaving and error correction.
     */
    fun decode(data: ByteArray): ByteArray {
        if (data.size != capacity.total) {
            throw QRDecodingException("interleave.decode: len(data)=${data.size}, total=${capacity.total}")
        }

        val blocks = mutableListOf<ByteArray>()
        for (i in 0 until capacity.numBlocks) {
            val isShort = i < capacity.shortBlocks
            blocks.add(ByteArray(capacity.words + capacity.blockLen + (if (isShort) 0 else 1)))
        }

        // Short blocks data
        var pos = 0
        for (i in 0 until capacity.blockLen) {
            for (j in 0 until capacity.numBlocks) {
                blocks[j][i] = data[pos++]
            }
        }

        // Long blocks extra data byte
        for (j in capacity.shortBlocks until capacity.numBlocks) {
            blocks[j][capacity.blockLen] = data[pos++]
        }

        // ECC bytes
        for (i in capacity.blockLen until capacity.blockLen + capacity.words) {
            for (j in 0 until capacity.numBlocks) {
                val isShort = j < capacity.shortBlocks
                blocks[j][i + (if (isShort) 0 else 1)] = data[pos++]
            }
        }

        // Decode each block and concatenate
        val result = mutableListOf<Byte>()
        for (block in blocks) {
            val decoded = rs.decode(block)
            // Take only data bytes, not ECC bytes
            val dataLen = block.size - capacity.words
            for (i in 0 until dataLen) {
                result.add(decoded[i])
            }
        }

        return result.toByteArray()
    }
}
