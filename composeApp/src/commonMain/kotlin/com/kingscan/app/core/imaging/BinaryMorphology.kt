package com.kingscan.app.core.imaging

/** Square dilation with clipped borders; process 32 neighboring pixels per word. */
internal object BinaryMorphology {
    fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int,
               inverted: Boolean = false): BooleanArray {
        require(radius in 1..3)
        val stride = (width + 31) / 32
        val packed = IntArray(stride * height)
        for (y in 0 until height) {
            val row = y * width
            for (word in 0 until stride) {
                val first = word * 32
                var bits = 0
                for (bit in 0 until minOf(32, width - first)) {
                    val value = if (mask[row + first + bit] != inverted) 1 else 0
                    bits = bits or (value shl bit)
                }
                packed[y * stride + word] = bits
            }
        }
        val horizontal = IntArray(packed.size)
        for (y in 0 until height) {
            val row = y * stride
            for (word in 0 until stride) {
                val center = packed[row + word]
                val left = if (word > 0) packed[row + word - 1] else 0
                val right = if (word + 1 < stride) packed[row + word + 1] else 0
                var bits = center
                for (offset in 1..radius) {
                    bits = bits or (center shl offset) or (center ushr offset) or
                        (left ushr (32 - offset)) or (right shl (32 - offset))
                }
                horizontal[row + word] = bits
            }
        }
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val row = y * width
            for (word in 0 until stride) {
                var bits = 0
                for (sy in (y - radius).coerceAtLeast(0)..(y + radius).coerceAtMost(height - 1)) {
                    bits = bits or horizontal[sy * stride + word]
                }
                val first = word * 32
                for (bit in 0 until minOf(32, width - first)) {
                    out[row + first + bit] = (bits ushr bit) and 1 != 0
                }
            }
        }
        return out
    }
}
