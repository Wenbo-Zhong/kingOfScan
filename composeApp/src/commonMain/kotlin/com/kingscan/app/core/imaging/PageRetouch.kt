package com.kingscan.app.core.imaging

import com.kingscan.app.core.document.*
import kotlin.math.*

/** Deterministic local restoration. All masks use corrected-page coordinates. */
object PageRetouch {
    fun apply(source: RgbaImage, edits: List<RetouchEdit>, orientation: Int = 0,
        progress: (Float) -> Unit = {}, checkActive: () -> Unit = {}): RgbaImage = edits.foldIndexed(source) { index, image, edit ->
        checkActive()
        when (edit.kind) {
            RetouchKind.Erase -> fill(image, strokeMask(image, edit.strokes))
            RetouchKind.ColoredInk -> {
                val mask = BooleanArray(image.pixels.size) { index ->
                    val p = image.pixels[index]
                    val high = maxOf(p.red(), p.green(), p.blue())
                    val low = minOf(p.red(), p.green(), p.blue())
                    high - low > (70 - edit.strength * 35) && high > 80 &&
                        (p.red() > p.green() * 1.2f || p.blue() > p.green() * 1.15f)
                }
                // Include antialiased coloured edges, but preserve nearby black print.
                val expanded = mask.copyOf()
                for (y in 1 until image.height - 1) for (x in 1 until image.width - 1) {
                    val i = y * image.width + x; val p = image.pixels[i]
                    if (maxOf(p.red(), p.green(), p.blue()) - minOf(p.red(), p.green(), p.blue()) > 18 &&
                        (mask[i - 1] || mask[i + 1] || mask[i - image.width] || mask[i + image.width])) expanded[i] = true
                }
                fill(image, expanded)
            }
            RetouchKind.Denoise -> denoise(image, edit.strength)
            RetouchKind.Deblur -> deblur(image, edit.strength)
            else -> {
                // Reproduce each model's original coordinate frame after a page rotation.
                val turns = (orientation - edit.orientation).mod(4)
                var canonical = image
                repeat((4 - turns).mod(4)) { canonical = canonical.rotate90CW() }
                var result = DocumentAi.apply(canonical, edit,
                    { progress((index + it) / edits.size) }, checkActive)
                repeat(turns) { result = result.rotate90CW() }
                result
            }
        }
    }

    internal fun strokeMask(image: RgbaImage, strokes: List<RetouchStroke>): BooleanArray {
        val mask = BooleanArray(image.pixels.size)
        for (stroke in strokes) {
            val points = stroke.points.map { it.clamped() }
            val segments = if (points.size == 1) listOf(points[0] to points[0]) else points.zipWithNext()
            val radius = (stroke.width * minOf(image.width, image.height) / 2).coerceAtLeast(1f)
            for ((a, b) in segments) {
                val ax = a.x * (image.width - 1); val ay = a.y * (image.height - 1)
                val bx = b.x * (image.width - 1); val by = b.y * (image.height - 1)
                val dx = bx - ax; val dy = by - ay; val length = dx * dx + dy * dy
                for (y in floor(minOf(ay, by) - radius).toInt().coerceAtLeast(0)..ceil(maxOf(ay, by) + radius).toInt().coerceAtMost(image.height - 1))
                    for (x in floor(minOf(ax, bx) - radius).toInt().coerceAtLeast(0)..ceil(maxOf(ax, bx) + radius).toInt().coerceAtMost(image.width - 1)) {
                        val t = if (length == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
                        val ex = x - ax - t * dx; val ey = y - ay - t * dy
                        if (ex * ex + ey * ey <= radius * radius) mask[y * image.width + x] = true
                    }
            }
        }
        return mask
    }

    /** Fast boundary propagation followed by harmonic relaxation; never changes unmasked pixels. */
    internal fun fill(source: RgbaImage, mask: BooleanArray, maxFraction: Float = 0.4f): RgbaImage {
        val count = mask.count { it }
        if (count == 0) return source
        require(count < source.pixels.size * maxFraction) { "擦除区域过大，请降低识别强度或分次涂抹，并保留周围纸面" }
        val width = source.width; val height = source.height
        val state = ByteArray(mask.size) { if (mask[it]) 0 else 2 }
        val queue = IntArray(count)
        val out = source.pixels.copyOf()
        var size = 0
        fun neighbors(i: Int, action: (Int) -> Unit) {
            val x = i % width; val y = i / width
            if (x > 0) action(i - 1)
            if (x < width - 1) action(i + 1)
            if (y > 0) action(i - width)
            if (y < height - 1) action(i + width)
        }
        for (i in mask.indices) if (mask[i]) {
            var edge = false
            neighbors(i) { if (!mask[it]) edge = true }
            if (edge) { state[i] = 1; queue[size++] = i }
        }
        require(size > 0) { "擦除区域缺少可用背景" }
        var position = 0
        while (position < size) {
            val i = queue[position++]
            var red = 0; var green = 0; var blue = 0; var weight = 0
            neighbors(i) { n ->
                if (state[n].toInt() == 2) {
                    val p = out[n]; red += p.red(); green += p.green(); blue += p.blue(); weight++
                }
            }
            if (weight > 0) out[i] = argb(255, red / weight, green / weight, blue / weight)
            state[i] = 2
            neighbors(i) { if (state[it].toInt() == 0) { state[it] = 1; queue[size++] = it } }
        }
        // Alternating traversal avoids a directional bias in the filled paper background.
        repeat(24) { iteration ->
            for (j in queue.indices) {
                val i = queue[if (iteration % 2 == 0) j else queue.lastIndex - j]
                var r = 0; var g = 0; var b = 0; var n = 0
                neighbors(i) { val p = out[it]; r += p.red(); g += p.green(); b += p.blue(); n++ }
                if (n > 0) out[i] = argb(255, r / n, g / n, b / n)
            }
        }
        return RgbaImage(width, height, out)
    }

    private fun denoise(source: RgbaImage, strength: Float): RgbaImage {
        if (maxOf(source.width, source.height) > 768) return tiled(source, 4) { denoise(it, strength) }
        val tolerance = 10f + strength.coerceIn(0f, 1f) * 45
        fun pass(input: IntArray, horizontal: Boolean): IntArray = IntArray(input.size) { i ->
            val x = i % source.width; val y = i / source.width
            val center = input[i]; val centerY = luminance(center)
            var r = 0f; var g = 0f; var b = 0f; var sum = 0f
            for (offset in -2..2) {
                val nx = if (horizontal) (x + offset).coerceIn(0, source.width - 1) else x
                val ny = if (horizontal) y else (y + offset).coerceIn(0, source.height - 1)
                val p = input[ny * source.width + nx]
                val diff = luminance(p) - centerY
                val weight = exp(-offset * offset / 3f - diff * diff / (2 * tolerance * tolerance))
                r += p.red() * weight; g += p.green() * weight; b += p.blue() * weight; sum += weight
            }
            argb(255, (r / sum).roundToInt(), (g / sum).roundToInt(), (b / sum).roundToInt())
        }
        return RgbaImage(source.width, source.height, pass(pass(source.pixels, true), false))
    }

    /** Richardson–Lucy restoration of mild defocus using a fixed, normalized Gaussian PSF. */
    private fun deblur(source: RgbaImage, strength: Float): RgbaImage {
        if (maxOf(source.width, source.height) > 768) return tiled(source, 56) { deblur(it, strength) }
        val observed = FloatArray(source.pixels.size) { luminance(source.pixels[it]).coerceAtLeast(1f) }
        var estimate = observed.copyOf()
        val sigma = 0.55f + strength.coerceIn(0f, 1f) * 0.65f
        val kernel = FloatArray(5) { exp(-(it - 2f).pow(2) / (2 * sigma * sigma)) }
        val total = kernel.sum(); for (i in kernel.indices) kernel[i] /= total
        fun blur(input: FloatArray): FloatArray {
            val horizontal = FloatArray(input.size) { i ->
                val x = i % source.width; val row = i - x
                kernel.indices.sumOf { k -> (input[row + (x + k - 2).coerceIn(0, source.width - 1)] * kernel[k]).toDouble() }.toFloat()
            }
            return FloatArray(input.size) { i ->
                val x = i % source.width; val y = i / source.width
                kernel.indices.sumOf { k -> (horizontal[(y + k - 2).coerceIn(0, source.height - 1) * source.width + x] * kernel[k]).toDouble() }.toFloat()
            }
        }
        repeat(5 + (strength.coerceIn(0f, 1f) * 8).toInt()) {
            val prediction = blur(estimate)
            val correction = blur(FloatArray(prediction.size) { observed[it] / prediction[it].coerceAtLeast(1f) })
            estimate = FloatArray(estimate.size) { (estimate[it] * correction[it].coerceIn(0.6f, 1.7f)).coerceIn(0f, 255f) }
        }
        return RgbaImage(source.width, source.height, IntArray(source.pixels.size) { i ->
            val p = source.pixels[i]; val delta = estimate[i] - observed[i]
            fun channel(value: Int) = (value + delta).roundToInt().coerceIn(0, 255)
            argb(255, channel(p.red()), channel(p.green()), channel(p.blue()))
        })
    }

    private fun tiled(source: RgbaImage, padding: Int, transform: (RgbaImage) -> RgbaImage): RgbaImage {
        val out = IntArray(source.pixels.size)
        for (top in 0 until source.height step 512) for (left in 0 until source.width step 512) {
            val right = minOf(left + 512, source.width); val bottom = minOf(top + 512, source.height)
            val x0 = maxOf(0, left - padding); val y0 = maxOf(0, top - padding)
            val x1 = minOf(source.width, right + padding); val y1 = minOf(source.height, bottom + padding)
            val width = x1 - x0
            val tile = RgbaImage(width, y1 - y0, IntArray(width * (y1 - y0)) { i ->
                source.pixels[(i / width + y0) * source.width + i % width + x0]
            })
            val restored = transform(tile)
            for (y in top until bottom) for (x in left until right)
                out[y * source.width + x] = restored.pixel(x - x0, y - y0)
        }
        return RgbaImage(source.width, source.height, out)
    }

    private fun luminance(p: Int): Float = (p.red() * 77 + p.green() * 150 + p.blue() * 29) / 256f
}
