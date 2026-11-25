package com.kingscan.app

import com.kingscan.app.imaging.DocumentDetector
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.imaging.argb
import com.kingscan.app.model.PointF
import com.kingscan.app.model.Quad
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentEdgesTest {
    @Test
    fun printedGrayPageOnSimilarDeskUsesOuterEdges() {
        val expected = Quad(PointF(0.15f, 0.25f), PointF(0.82f, 0.21f),
            PointF(0.89f, 0.76f), PointF(0.09f, 0.81f))
        var image = printedPage(expected, paper = 208, desk = 232)
        var reference = expected
        repeat(4) {
            assertCorners(reference, DocumentDetector.detectOrNull(image))
            image = image.rotate90CW()
            reference = reference.rotate90CW()
        }
    }

    @Test
    fun lowContrastOutlineSurvivesDarkPrintedContent() {
        val expected = Quad.inset(0.18f)
        assertCorners(expected, DocumentDetector.detectOrNull(printedPage(expected, paper = 220, desk = 228)))
    }

    @Test
    fun roundedCornersAndSideNotchUseExtendedStraightEdges() {
        val image = RgbaImage.filled(400, 300, gray(232))
        for (y in 70..245) {
            for (x in 70..330) {
                val dx = x - x.coerceIn(90, 310)
                val dy = y - y.coerceIn(90, 225)
                val notchX = x - 70
                val notchY = y - 157
                if (dx * dx + dy * dy <= 400 && notchX * notchX + notchY * notchY > 400) {
                    image.pixels[y * image.width + x] = gray(205)
                }
            }
        }
        for (y in 95..220) {
            for (x in 105..300) {
                if (y % 30 < 8) image.pixels[y * image.width + x] = gray(35)
            }
        }
        assertCorners(Quad(PointF(70f / 400, 70f / 300), PointF(330f / 400, 70f / 300),
            PointF(330f / 400, 245f / 300), PointF(70f / 400, 245f / 300)),
            DocumentDetector.detectOrNull(image))
    }

    @Test
    fun printedRuleAcrossThePageDoesNotCutTheDetectedOutlineInHalf() {
        val expected = Quad.inset(0.18f)
        val image = printedPage(expected, paper = 208, desk = 232)
        for (y in 239..241) {
            for (x in 65..294) image.pixels[y * image.width + x] = gray(30)
        }
        assertCorners(expected, DocumentDetector.detectOrNull(image))
    }

    @Test
    fun blankGradientAndRoundObjectHaveNoDocumentOutline() {
        val gradient = RgbaImage(360, 480, IntArray(360 * 480) { gray(190 + it / 360 / 12) })
        assertNull(DocumentDetector.detectOrNull(gradient))
        val circle = RgbaImage.filled(360, 480, gray(232))
        for (y in 0 until circle.height) {
            for (x in 0 until circle.width) {
                if ((x - 180) * (x - 180) + (y - 240) * (y - 240) <= 90 * 90) {
                    circle.pixels[y * circle.width + x] = gray(60)
                }
            }
        }
        assertNull(DocumentDetector.detectOrNull(circle))
    }

    @Test
    fun pageMissingAnOuterEdgeDoesNotSelectTheDesk() {
        val image = RgbaImage.filled(360, 480, gray(232))
        for (y in 100..380) {
            for (x in 0..300) image.pixels[y * image.width + x] = gray(205)
        }
        assertNull(DocumentDetector.detectOrNull(image))
    }

    private fun printedPage(quad: Quad, paper: Int, desk: Int): RgbaImage {
        val width = 360
        val height = 480
        val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        return RgbaImage(width, height, IntArray(width * height) { index ->
            val x = (index % width).toFloat() / width
            val y = (index / width).toFloat() / height
            val inside = points.indices.all { i ->
                val a = points[i]
                val b = points[(i + 1) % 4]
                (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0
            }
            val ink = x in 0.25f..0.68f && y in 0.33f..0.67f && index / width % 40 < 26
            gray(if (inside) { if (ink) 35 else paper } else desk)
        })
    }

    private fun gray(value: Int) = argb(255, value, value, value)

    private fun assertCorners(expected: Quad, actual: Quad?) {
        val found = assertNotNull(actual, "Complete document outline was missed")
        val reference = listOf(expected.topLeft, expected.topRight, expected.bottomRight, expected.bottomLeft)
        val detected = listOf(found.topLeft, found.topRight, found.bottomRight, found.bottomLeft)
        reference.zip(detected).forEachIndexed { index, (a, b) ->
            assertTrue(abs(a.x - b.x) < 0.02f && abs(a.y - b.y) < 0.02f,
                "Corner $index: expected $a, got $b")
        }
    }
}
