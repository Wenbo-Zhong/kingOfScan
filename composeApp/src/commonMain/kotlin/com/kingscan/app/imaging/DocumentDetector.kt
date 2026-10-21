package com.kingscan.app.imaging

import com.kingscan.app.model.PointF
import com.kingscan.app.model.Quad

object DocumentDetector {
    /**
     * Finds a document quad on a downscaled copy of [image].
     * Paper is assumed to be a large region that does not cover every image edge.
     * Both bright-on-dark and dark-on-light pages are considered. When nothing
     * stands out, a slightly inset rectangle is returned so the user can drag it.
     */
    fun detect(image: RgbaImage): Quad {
        return detectOrNull(image) ?: Quad.inset(0.06f)
    }

    /** Returns null when the preview has no clear page-sized region. */
    fun detectOrNull(image: RgbaImage): Quad? {
        val small = image.scaledDown(ScanLimits.DETECT_EDGE)
        val gray = grayscale(small)
        val blurred = boxBlur(gray, small.width, small.height, radius = 1)
        val threshold = otsu(blurred)
        val bright = BooleanArray(blurred.size) { blurred[it] >= threshold }
        val dark = BooleanArray(blurred.size) { blurred[it] < threshold }
        val chosen = selectComponent(
            bright = largestComponent(bright, small.width, small.height),
            dark = largestComponent(dark, small.width, small.height),
            width = small.width,
            height = small.height,
        ) ?: return null
        return quadFromComponent(chosen, small.width, small.height)
    }

    internal fun selectComponent(
        bright: IntArray,
        dark: IntArray,
        width: Int,
        height: Int,
    ): IntArray? {
        val total = width * height
        fun score(component: IntArray): Long {
            if (component.isEmpty()) return Long.MIN_VALUE
            val ratio = component.size.toFloat() / total.toFloat()
            if (ratio < 0.08f || ratio > 0.92f) return Long.MIN_VALUE
            val borders = bordersTouched(component, width, height)
            return (4L - borders) * 1_000_000L + component.size
        }

        val brightScore = score(bright)
        val darkScore = score(dark)
        if (maxOf(brightScore, darkScore) == Long.MIN_VALUE) return null
        return if (brightScore >= darkScore) bright else dark
    }

    private fun quadFromComponent(component: IntArray, width: Int, height: Int): Quad {
        var topLeft = component[0]
        var topRight = component[0]
        var bottomRight = component[0]
        var bottomLeft = component[0]
        var topLeftScore = Int.MAX_VALUE
        var topRightScore = Int.MIN_VALUE
        var bottomRightScore = Int.MIN_VALUE
        var bottomLeftScore = Int.MAX_VALUE
        for (index in component) {
            val x = index % width
            val y = index / width
            val sum = x + y
            val diff = x - y
            if (sum < topLeftScore) {
                topLeftScore = sum
                topLeft = index
            }
            if (sum > bottomRightScore) {
                bottomRightScore = sum
                bottomRight = index
            }
            if (diff > topRightScore) {
                topRightScore = diff
                topRight = index
            }
            if (diff < bottomLeftScore) {
                bottomLeftScore = diff
                bottomLeft = index
            }
        }
        fun point(index: Int) = PointF(
            x = (index % width).toFloat() / width.toFloat(),
            y = (index / width).toFloat() / height.toFloat(),
        )
        return Quad(point(topLeft), point(topRight), point(bottomRight), point(bottomLeft)).clamped()
    }

    private fun bordersTouched(component: IntArray, width: Int, height: Int): Int {
        var left = false
        var right = false
        var top = false
        var bottom = false
        for (index in component) {
            val x = index % width
            val y = index / width
            if (x == 0) left = true
            if (x == width - 1) right = true
            if (y == 0) top = true
            if (y == height - 1) bottom = true
            if (left && right && top && bottom) break
        }
        return listOf(left, right, top, bottom).count { it }
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

    internal fun largestComponent(mask: BooleanArray, width: Int, height: Int): IntArray {
        val seen = BooleanArray(mask.size)
        val queueX = IntArray(mask.size)
        val queueY = IntArray(mask.size)
        val component = IntArray(mask.size)
        var best = IntArray(0)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queueX[tail] = start % width
            queueY[tail] = start / width
            tail++
            seen[start] = true
            var count = 0
            while (head < tail) {
                val x = queueX[head]
                val y = queueY[head]
                head++
                component[count++] = y * width + x
                fun push(nx: Int, ny: Int) {
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) return
                    val index = ny * width + nx
                    if (seen[index] || !mask[index]) return
                    seen[index] = true
                    queueX[tail] = nx
                    queueY[tail] = ny
                    tail++
                }
                push(x - 1, y)
                push(x + 1, y)
                push(x, y - 1)
                push(x, y + 1)
            }
            if (count > best.size) best = component.copyOf(count)
        }
        return best
    }
}

object ScanLimits {
    const val MAX_EDGE = 1800
    const val PREVIEW_EDGE = 1000
    const val DETECT_EDGE = 480
    const val JPEG_QUALITY = 88
    const val THUMB_EDGE = 480
}
