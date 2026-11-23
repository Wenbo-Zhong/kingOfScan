package com.kingscan.app

import com.kingscan.app.imaging.*
import com.kingscan.app.model.*
import kotlin.math.abs
import kotlin.test.*

class CaptureGeometryTest {
    private fun frame(time: Long, shifted: Boolean = false, luminance: Float = 180f, sharpness: Float = 100f) =
        CaptureFrame(Quad.inset(0.1f), 480, 640, time, luminance, sharpness,
            IntArray(768) { if ((it % 7 == 0) != shifted) 40 else 220 })

    @Test fun automaticCaptureWaitsForStableQualityAndNewPage() {
        val controller = AutoCaptureController()
        assertFalse(controller.update(frame(0, luminance = 30f), true, false).capture)
        assertFalse(controller.update(frame(150, sharpness = 3f), true, false).capture)
        val results = (1..10).map { controller.update(frame(300 + it * 150L), true, false) }
        assertEquals(1, results.count { it.capture })
        assertFalse(controller.update(frame(1950), true, false).capture)
        // A brighter exposure of the same content must remain latched.
        val brighter = frame(2100).let { it.copy(fingerprint = it.fingerprint.map { p -> p + 20 }.toIntArray()) }
        repeat(4) { assertFalse(controller.update(brighter.copy(timeMillis = 2100 + it * 150L), true, false).capture) }
        val afterTurn = (1..13).map { controller.update(frame(3000 + it * 150L, shifted = true), true, false) }
        assertEquals(1, afterTurn.count { it.capture })
        assertFalse(controller.update(frame(5100, shifted = true), true, true).capture)
    }

    @Test fun actualPageDetectorFindsBimodalPaperAndRejectsBlankFrame() {
        val pixels = IntArray(320 * 240) { index ->
            val x = index % 320; val y = index / 320
            if (x in 45..275 && y in 30..210) 0xFFF7F7F7.toInt() else 0xFF303030.toInt()
        }
        val detected = assertNotNull(CaptureAnalysis.analyze(RgbaImage(320, 240, pixels), 100).quad)
        assertTrue(abs(detected.topLeft.x - 45f / 320) < 0.025f)
        assertTrue(abs(detected.bottomRight.y - 210f / 240) < 0.025f)
        assertNull(DocumentDetector.detectOrNull(RgbaImage.filled(320, 240, 0xFFFFFFFF.toInt())))
    }

    @Test fun bookCurveFlattensMeasuredTextBaselinesAndSeparatesPages() {
        val width = 600; val height = 400
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            if (x >= width / 2) 0xFFFFE0C0.toInt() else {
                val normalized = x.toFloat() / (width / 2 - 1)
                val displacement = 40 * normalized * normalized
                val onLine = listOf(110f, 210f, 310f).any { abs(y - (it + displacement)) < 2f }
                if (onLine) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val spread = RgbaImage(width, height, pixels)
        val corrected = BookGeometry.flatten(spread, BookPageEdit(topCurve = 0.1f, bottomCurve = 0.1f))
        val positions = (20 until corrected.width - 20 step 12).map { x ->
            (85..150).minBy { y -> corrected.pixel(x, y).red() }
        }
        assertTrue(positions.max() - positions.min() <= 3, "Flattened line varies ${positions.max() - positions.min()} pixels")
        val right = BookGeometry.flatten(spread, BookPageEdit(rightPage = true))
        assertEquals(255, right.pixel(right.width / 2, 100).red())
        assertEquals(224, right.pixel(right.width / 2, 100).green())
        val rotated = PageGeometry.apply(spread, PageEdit(quad = Quad.full(), book = BookPageEdit(), outputQuarterTurns = 1))
        assertEquals(height, rotated.width)
        assertEquals(width / 2, rotated.height)
    }
}
