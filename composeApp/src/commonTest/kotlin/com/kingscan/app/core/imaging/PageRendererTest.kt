package com.kingscan.app.core.imaging

import com.kingscan.app.core.document.*
import kotlin.test.*

class PageRendererTest {
    private val image = RgbaImage(16, 24, IntArray(16 * 24) { argb(255, it % 180, 50, 100) })

    @Test fun adjustedBaseSkipsGeometryRetouchAndFilter() {
        val edit = PageEdit(quad = Quad.inset(0.2f), outputQuarterTurns = 1, filter = FilterType.Invert,
            adjustments = ImageAdjustments(brightness = 0.5f))
        assertSame(image, PageRenderer.base(image, edit, PageStage.Adjusted))
    }

    @Test fun originalAndGeometricStagesProduceTheSameOutput() {
        val edit = PageEdit(quad = Quad.inset(0.1f), outputQuarterTurns = 1, filter = FilterType.Gray,
            adjustments = ImageAdjustments(contrast = 0.2f))
        val original = PageRenderer.render(image, edit, PageStage.OrientedOriginal)
        val geometric = PageRenderer.render(PageGeometry.apply(image, edit), edit, PageStage.Geometry)
        assertEquals(original.width, geometric.width)
        assertEquals(original.height, geometric.height)
        assertContentEquals(original.pixels, geometric.pixels)
    }

    @Test fun encodingKeepsUnmarkedBaseAndOriginalButInvalidatesOcr() {
        val encodedImages = mutableListOf<RgbaImage>()
        val codec = object : ImageCodec {
            override fun decode(bytes: ByteArray, maxEdge: Int) = image
            override fun encodeJpeg(image: RgbaImage, quality: Int): ByteArray {
                encodedImages += image
                return byteArrayOf(encodedImages.size.toByte())
            }
        }
        val original = byteArrayOf(8, 9)
        val edit = PageEdit(filter = FilterType.Original, marks = listOf(
            PageMark("redact", MarkType.Redaction, listOf(PointF(0.2f, 0.2f), PointF(0.8f, 0.8f)))))
        val page = PageRenderer.encode(codec, image, edit, PageStage.Adjusted, original)
        assertContentEquals(original, page.originalBytes)
        assertContentEquals(byteArrayOf(2), page.annotationBaseBytes)
        assertNull(page.ocr)
        assertContentEquals(image.pixels, encodedImages[1].pixels)
        assertFalse(image.pixels.contentEquals(encodedImages[0].pixels))
    }
}
