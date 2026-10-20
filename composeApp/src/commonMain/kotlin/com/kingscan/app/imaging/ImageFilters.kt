package com.kingscan.app.imaging

import com.kingscan.app.model.FilterType
import kotlin.math.roundToInt

object ImageFilters {
    fun apply(source: RgbaImage, filter: FilterType): RgbaImage = when (filter) {
        FilterType.Original -> source
        FilterType.Enhance -> enhance(source)
        FilterType.Gray -> map(source) { pixel ->
            val y = luma(pixel)
            argb(255, y, y, y)
        }
        FilterType.BlackWhite -> blackAndWhite(source)
        FilterType.Soft -> map(source) { pixel ->
            fun lift(channel: Int): Int = (channel * 0.92f + 22f).roundToInt().coerceIn(0, 255)
            argb(255, lift(pixel.red()), lift(pixel.green()), lift(pixel.blue()))
        }
    }

    private fun enhance(source: RgbaImage): RgbaImage {
        val samples = IntArray(source.pixels.size)
        for (i in source.pixels.indices) samples[i] = luma(source.pixels[i])
        samples.sort()
        val low = samples[samples.size * 2 / 100].coerceAtMost(230)
        val high = samples[samples.size * 98 / 100].coerceAtLeast(low + 8)
        val scale = 255f / (high - low).toFloat()
        return map(source) { pixel ->
            fun stretch(channel: Int): Int {
                return ((channel - low) * scale).roundToInt().coerceIn(0, 255)
            }
            val r = stretch(pixel.red())
            val g = stretch(pixel.green())
            val b = stretch(pixel.blue())
            val y = (r * 77 + g * 150 + b * 29) shr 8
            fun sat(channel: Int): Int {
                return (y + (channel - y) * 1.12f).roundToInt().coerceIn(0, 255)
            }
            argb(255, sat(r), sat(g), sat(b))
        }
    }

    private fun blackAndWhite(source: RgbaImage): RgbaImage {
        val gray = IntArray(source.pixels.size) { luma(source.pixels[it]) }
        val threshold = (DocumentDetector.otsu(gray) - 8).coerceIn(1, 254)
        return map(source) { pixel ->
            val value = if (luma(pixel) >= threshold) 255 else 0
            argb(255, value, value, value)
        }
    }

    private fun map(source: RgbaImage, transform: (Int) -> Int): RgbaImage {
        val out = IntArray(source.pixels.size)
        for (i in source.pixels.indices) out[i] = transform(source.pixels[i])
        return RgbaImage(source.width, source.height, out)
    }

    private fun luma(pixel: Int): Int = (pixel.red() * 77 + pixel.green() * 150 + pixel.blue() * 29) shr 8
}
