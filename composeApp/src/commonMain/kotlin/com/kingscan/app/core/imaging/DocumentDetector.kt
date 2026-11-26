package com.kingscan.app.core.imaging

import com.kingscan.app.core.document.Quad

object DocumentDetector {
    /**
     * Finds a document quad on a downscaled copy of [image].
     * Four supported outer edges are required, independent of the page's color.
     * When nothing stands out, a slightly inset rectangle is returned so the user
     * can drag it.
     */
    fun detect(image: RgbaImage): Quad {
        return detectOrNull(image) ?: Quad.inset(0.06f)
    }

    /** Returns null when the preview has no supported, closed document outline. */
    fun detectOrNull(image: RgbaImage, preferred: Quad? = null): Quad? {
        val small = image.scaledDown(ScanLimits.DETECT_EDGE)
        return DocumentEdges(grayscale(small), small.width, small.height).detect(preferred)
    }

    private fun grayscale(image: RgbaImage): IntArray {
        val out = IntArray(image.pixels.size)
        for (i in image.pixels.indices) {
            val pixel = image.pixels[i]
            out[i] = (pixel.red() * 77 + pixel.green() * 150 + pixel.blue() * 29) shr 8
        }
        return out
    }

    internal fun boxBlur(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return src
        val horizontal = IntArray(src.size)
        val out = IntArray(src.size)
        val window = radius * 2 + 1
        for (y in 0 until height) {
            var sum = 0
            val row = y * width
            for (k in -radius..radius) {
                sum += src[row + k.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                horizontal[row + x] = sum / window
                val remove = (x - radius).coerceIn(0, width - 1)
                val add = (x + radius + 1).coerceIn(0, width - 1)
                sum += src[row + add] - src[row + remove]
            }
        }
        for (x in 0 until width) {
            var sum = 0
            for (k in -radius..radius) {
                sum += horizontal[k.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                out[y * width + x] = sum / window
                val remove = (y - radius).coerceIn(0, height - 1)
                val add = (y + radius + 1).coerceIn(0, height - 1)
                sum += horizontal[add * width + x] - horizontal[remove * width + x]
            }
        }
        return out
    }

    internal fun otsu(gray: IntArray): Int {
        val histogram = IntArray(256)
        for (value in gray) histogram[value.coerceIn(0, 255)]++
        val total = gray.size
        var sum = 0.0
        for (i in 0..255) sum += i * histogram[i]
        var sumBackground = 0.0
        var weightBackground = 0
        var best = -1.0
        var threshold = 128
        for (t in 0..255) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += t * histogram[t]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sum - sumBackground) / weightForeground
            val between = weightBackground.toDouble() * weightForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }
}

object ScanLimits {
    const val MAX_EDGE = 1800
    const val PREVIEW_EDGE = 1000
    const val DETECT_EDGE = 480
    const val JPEG_QUALITY = 88
    const val THUMB_EDGE = 480
}
