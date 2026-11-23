package com.kingscan.app

import com.kingscan.app.imaging.*
import com.kingscan.app.model.*
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class PageRetouchTest {
    @Test fun selectedInkIsFilledAndEveryUnselectedPixelIsUnchanged() {
        val clean = RgbaImage(240, 180, IntArray(240 * 180) { i ->
            val x = i % 240; val y = i / 240
            val value = if (x in 20..80 && y in 30..35) 20 else 210 + x / 16
            argb(255, value, value, value)
        })
        val dirty = RgbaImage(clean.width, clean.height, clean.pixels.copyOf())
        for (y in 75..90) for (x in 105..125) dirty.pixels[y * 240 + x] = 0xFF111111.toInt()
        val stroke = RetouchStroke(listOf(PointF(115 / 239f, 80 / 179f), PointF(115 / 239f, 88 / 179f)), 0.16f)
        val effect = RetouchEdit(strokes = listOf(stroke))
        val mask = PageRetouch.strokeMask(dirty, effect.strokes)
        val restored = PageRetouch.apply(dirty, listOf(effect))
        var error = 0f; var n = 0
        for (i in clean.pixels.indices) {
            if (mask[i]) { error += abs(clean.pixels[i].red() - restored.pixels[i].red()); n++ }
            else assertEquals(dirty.pixels[i], restored.pixels[i])
        }
        assertTrue(error / n < 3, "Paper restoration error ${error / n}")
        assertEquals(20, restored.pixel(40, 32).red())
        assertTrue(dirty.pixel(115, 82).red() < 30)
        assertTrue(restored.pixel(115, 82).red() > 200)
        assertFailsWith<IllegalArgumentException> { PageRetouch.fill(clean, BooleanArray(clean.pixels.size) { true }) }
    }

    @Test fun coloredInkRemovalPreservesBlackPrinting() {
        val input = RgbaImage(200, 120, IntArray(200 * 120) { i ->
            val x = i % 200; val y = i / 200
            when {
                x in 20..80 && y in 20..25 -> 0xFF181818.toInt()
                x in 20..80 && y in 50..55 -> 0xFFDD2222.toInt()
                x in 20..80 && y in 80..85 -> 0xFF2244DD.toInt()
                else -> 0xFFF8F8F8.toInt()
            }
        })
        val output = PageRetouch.apply(input, listOf(RetouchEdit(RetouchKind.ColoredInk)))
        assertEquals(input.pixel(40, 22), output.pixel(40, 22))
        assertTrue(output.pixel(40, 52).green() > 235)
        assertTrue(output.pixel(40, 82).red() > 235)
    }

    @Test fun restorationReducesNoiseAndRecoversMildGaussianDefocus() {
        val random = Random(86)
        val noisy = RgbaImage(150, 100, IntArray(150 * 100) { argb(255, 210 + random.nextInt(-12, 13), 210, 210) })
        val denoised = PageRetouch.apply(noisy, listOf(RetouchEdit(RetouchKind.Denoise, strength = 0.8f)))
        fun error(image: RgbaImage) = image.pixels.sumOf { abs(it.red() - 210).toDouble() } / image.pixels.size
        assertTrue(error(denoised) < error(noisy) * 0.7)
        val clean = RgbaImage(160, 80, IntArray(160 * 80) { i ->
            val value = if ((i % 160) % 24 in 9..14) 30 else 220
            argb(255, value, value, value)
        })
        val sigma = 0.875f
        val kernel = FloatArray(5) { exp(-(it - 2f).pow(2) / (2 * sigma * sigma)) }
        val total = kernel.sum()
        val blurred = RgbaImage(160, 80, IntArray(160 * 80) { i ->
            val x = i % 160; val row = i - x
            val value = (kernel.indices.sumOf { k ->
                (clean.pixels[row + (x + k - 2).coerceIn(0, 159)].red() * kernel[k] / total).toDouble()
            }).roundToInt()
            argb(255, value, value, value)
        })
        val restored = PageRetouch.apply(blurred, listOf(RetouchEdit(RetouchKind.Deblur, strength = 0.5f)))
        fun againstClean(image: RgbaImage) = image.pixels.indices.sumOf { abs(image.pixels[it].red() - clean.pixels[it].red()).toDouble() }
        assertTrue(againstClean(restored) < againstClean(blurred) * 0.85, "Defocus error should fall")
    }
}
