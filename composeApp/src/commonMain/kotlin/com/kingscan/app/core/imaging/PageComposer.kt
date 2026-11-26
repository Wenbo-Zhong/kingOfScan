package com.kingscan.app.core.imaging

import com.kingscan.app.core.document.CompositionKind
import com.kingscan.app.core.document.CompositionOptions
import kotlin.math.ceil
import kotlin.math.roundToInt

object PageComposer {
    fun compose(images: List<RgbaImage>, options: CompositionOptions): RgbaImage {
        require(images.isNotEmpty() && images.size <= 30) { "请选择 1–30 页" }
        val width = options.outputWidth.coerceIn(600, 2400)
        val gap = options.gap.coerceIn(0, 120)
        return when (options.kind) {
            CompositionKind.IdCard -> {
                require(images.size == 2) { "证件复印件需要正、反面两页" }
                // 210 x 297 mm paper; each side has its physical 85.6 x 54 mm size.
                val height = (width * 297f / 210).roundToInt()
                val result = RgbaImage.filled(width, height, 0xFFFFFFFF.toInt())
                val cardWidth = width * 85.6f / 210
                val cardHeight = width * 54f / 210
                images.forEachIndexed { index, original ->
                    val image = if (original.width < original.height) original.rotate90CW() else original
                    draw(result, image, (width - cardWidth) / 2, width * (if (index == 0) 65f else 160f) / 210,
                        cardWidth, cardHeight, fill = true)
                }
                result
            }
            CompositionKind.Grid -> {
                val columns = options.columns.coerceIn(1, 4)
                val rows = ceil(images.size.toFloat() / columns).toInt()
                val height = (width * 297f / 210).roundToInt()
                val cellWidth = (width - gap * (columns + 1)).toFloat() / columns
                val cellHeight = (height - gap * (rows + 1)).toFloat() / rows
                require(cellWidth > 0 && cellHeight > 0) { "间距过大，请减小间距" }
                val result = RgbaImage.filled(width, height, 0xFFFFFFFF.toInt())
                images.forEachIndexed { index, image ->
                    draw(result, image, gap + index % columns * (cellWidth + gap),
                        gap + index / columns * (cellHeight + gap), cellWidth, cellHeight)
                }
                result
            }
            CompositionKind.Vertical -> {
                val inner = width - 2 * gap
                val heights = images.map { (inner.toFloat() * it.height / it.width).roundToInt() }
                val height = heights.sum() + gap * (images.size + 1)
                validateSize(width, height)
                val result = RgbaImage.filled(width, height, 0xFFFFFFFF.toInt())
                var top = gap
                images.forEachIndexed { index, image ->
                    draw(result, image, gap.toFloat(), top.toFloat(), inner.toFloat(), heights[index].toFloat())
                    top += heights[index] + gap
                }
                result
            }
            CompositionKind.Horizontal -> {
                val innerHeight = width - 2 * gap
                val widths = images.map { (innerHeight.toFloat() * it.width / it.height).roundToInt() }
                val resultWidth = widths.sum() + gap * (images.size + 1)
                validateSize(resultWidth, width)
                val result = RgbaImage.filled(resultWidth, width, 0xFFFFFFFF.toInt())
                var left = gap
                images.forEachIndexed { index, image ->
                    draw(result, image, left.toFloat(), gap.toFloat(), widths[index].toFloat(), innerHeight.toFloat())
                    left += widths[index] + gap
                }
                result
            }
            CompositionKind.Overlap -> OverlapStitcher.stitch(images)
        }
    }

    internal fun validateSize(width: Int, height: Int) {
        require(width in 1..16000 && height in 1..16000 && width.toLong() * height <= 12_000_000) {
            "成图尺寸过大，请减少页面或降低输出尺寸"
        }
    }

    private fun draw(canvas: RgbaImage, image: RgbaImage, left: Float, top: Float, width: Float, height: Float, fill: Boolean = false) {
        val scale = minOf(width / image.width, height / image.height)
        val drawWidth = if (fill) width else image.width * scale
        val drawHeight = if (fill) height else image.height * scale
        val x0 = (left + (width - drawWidth) / 2).roundToInt()
        val y0 = (top + (height - drawHeight) / 2).roundToInt()
        val w = drawWidth.roundToInt().coerceAtLeast(1); val h = drawHeight.roundToInt().coerceAtLeast(1)
        for (y in 0 until h) for (x in 0 until w) {
            if (x0 + x !in 0 until canvas.width || y0 + y !in 0 until canvas.height) continue
            canvas.pixels[(y0 + y) * canvas.width + x0 + x] =
                sampleBilinear(image, x.toFloat() / w * (image.width - 1), y.toFloat() / h * (image.height - 1))
        }
    }
}
