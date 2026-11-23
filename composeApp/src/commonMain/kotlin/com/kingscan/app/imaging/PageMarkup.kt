package com.kingscan.app.imaging

import com.kingscan.app.model.ImageAdjustments
import com.kingscan.app.model.MarkType
import com.kingscan.app.model.PageMark
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Stroke geometry, compositing and opaque redaction are shared by preview and final export. */
object PageMarkup {
    fun render(source: RgbaImage, marks: List<PageMark>): RgbaImage {
        val out = source.pixels.copyOf()
        fun blend(x: Int, y: Int, mark: PageMark, coverage: Float = 1f) {
            if (x !in 0 until source.width || y !in 0 until source.height) return
            val color = mark.color.toInt()
            val a = (mark.opacity * color.alpha() / 255f * coverage).coerceIn(0f, 1f)
            val old = out[y * source.width + x]
            fun channel(v: Int, o: Int) = (v * a + o * (1 - a)).roundToInt().coerceIn(0, 255)
            out[y * source.width + x] = argb(255, channel(color.red(), old.red()), channel(color.green(), old.green()), channel(color.blue(), old.blue()))
        }
        marks.filter { it.type != MarkType.Text }.forEach { mark ->
            if (mark.type == MarkType.Redaction && mark.points.size >= 2) {
                val first = mark.points.first().clamped()
                val last = mark.points.last().clamped()
                for (y in floor(minOf(first.y, last.y) * source.height).toInt() until ceil(maxOf(first.y, last.y) * source.height).toInt()) {
                    for (x in floor(minOf(first.x, last.x) * source.width).toInt() until ceil(maxOf(first.x, last.x) * source.width).toInt()) {
                        if (x in 0 until source.width && y in 0 until source.height) out[y * source.width + x] = 0xFF000000.toInt()
                    }
                }
            } else {
                val radius = (mark.strokeWidth * minOf(source.width, source.height) / 2).coerceAtLeast(0.75f)
                val points = mark.points.map { it.clamped() }
                val segments = if (points.size == 1) listOf(points[0] to points[0]) else points.zipWithNext()
                // A coverage mask prevents repeated alpha blending at stroke joins.
                val coverage = mutableMapOf<Int, Float>()
                segments.forEach { (a, b) ->
                    val ax = a.x * (source.width - 1); val ay = a.y * (source.height - 1)
                    val bx = b.x * (source.width - 1); val by = b.y * (source.height - 1)
                    val dx = bx - ax; val dy = by - ay
                    val length = dx * dx + dy * dy
                    for (y in floor(minOf(ay, by) - radius - 1).toInt().coerceAtLeast(0)..ceil(maxOf(ay, by) + radius + 1).toInt().coerceAtMost(source.height - 1)) {
                        for (x in floor(minOf(ax, bx) - radius - 1).toInt().coerceAtLeast(0)..ceil(maxOf(ax, bx) + radius + 1).toInt().coerceAtMost(source.width - 1)) {
                            val t = if (length == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
                            val distance = hypot(x - ax - t * dx, y - ay - t * dy)
                            val amount = (radius + 0.5f - distance).coerceIn(0f, 1f)
                            if (amount > 0) {
                                val key = y * source.width + x
                                coverage[key] = maxOf(coverage[key] ?: 0f, amount)
                            }
                        }
                    }
                }
                coverage.forEach { (index, amount) -> blend(index % source.width, index / source.width, mark, amount) }
            }
        }
        return drawPageText(RgbaImage(source.width, source.height, out), marks.filter { it.type == MarkType.Text })
    }

    fun adjust(source: RgbaImage, adjustments: ImageAdjustments): RgbaImage {
        if (adjustments == ImageAdjustments()) return source
        val contrast = (1f + adjustments.contrast).coerceIn(0f, 3f)
        val saturation = (1f + adjustments.saturation).coerceIn(0f, 3f)
        val brightness = adjustments.brightness.coerceIn(-1f, 1f) * 128
        return RgbaImage(source.width, source.height, IntArray(source.pixels.size) { index ->
            val p = source.pixels[index]
            val gray = (p.red() * 77 + p.green() * 150 + p.blue() * 29) / 256f
            fun channel(value: Int) = ((gray + (value - gray) * saturation - 128) * contrast + 128 + brightness).roundToInt().coerceIn(0, 255)
            argb(255, channel(p.red()), channel(p.green()), channel(p.blue()))
        })
    }
}

expect fun drawPageText(source: RgbaImage, marks: List<PageMark>): RgbaImage
