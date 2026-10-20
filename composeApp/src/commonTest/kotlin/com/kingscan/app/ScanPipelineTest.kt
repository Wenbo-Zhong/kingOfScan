package com.kingscan.app

import com.kingscan.app.data.DocumentRepository
import com.kingscan.app.imaging.DocumentDetector
import com.kingscan.app.imaging.ImageFilters
import com.kingscan.app.imaging.PerspectiveWarp
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.imaging.argb
import com.kingscan.app.imaging.blue
import com.kingscan.app.imaging.green
import com.kingscan.app.imaging.red
import com.kingscan.app.model.EncodedPage
import com.kingscan.app.model.FilterType
import com.kingscan.app.model.PointF
import com.kingscan.app.model.Quad
import com.kingscan.app.pdf.PdfImage
import com.kingscan.app.pdf.PdfWriter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.fakefilesystem.FakeFileSystem

class ScanPipelineTest {
    @Test
    fun detectsBrightPageOnDarkDesk() {
        val image = RgbaImage.filled(160, 200, argb(255, 20, 20, 20))
        fillRect(image, 30, 40, 130, 170, argb(255, 245, 245, 240))
        val quad = DocumentDetector.detect(image)
        assertNear(0.19f, quad.topLeft.x)
        assertNear(0.20f, quad.topLeft.y)
        assertNear(0.81f, quad.topRight.x)
        assertNear(0.20f, quad.topRight.y)
        assertNear(0.81f, quad.bottomRight.x)
        assertNear(0.85f, quad.bottomRight.y)
        assertNear(0.19f, quad.bottomLeft.x)
        assertNear(0.85f, quad.bottomLeft.y)
    }

    @Test
    fun detectsDarkCardOnLightTable() {
        val image = RgbaImage.filled(160, 200, argb(255, 240, 240, 240))
        fillRect(image, 40, 50, 120, 160, argb(255, 15, 15, 15))
        val quad = DocumentDetector.detect(image)
        assertTrue(quad.topLeft.x in 0.15f..0.35f)
        assertTrue(quad.bottomRight.x in 0.65f..0.85f)
        assertTrue(quad.topLeft.y in 0.15f..0.35f)
        assertTrue(quad.bottomRight.y in 0.70f..0.90f)
    }

    @Test
    fun rotatesPixelsAndQuadTogether() {
        val image = RgbaImage(2, 1, intArrayOf(1, 2))
        val rotated = image.rotate90CW()
        assertEquals(1, rotated.width)
        assertEquals(2, rotated.height)
        assertEquals(1, rotated.pixel(0, 0))
        assertEquals(2, rotated.pixel(0, 1))

        val quad = Quad(PointF(0.2f, 0.3f), PointF(0.8f, 0.3f), PointF(0.8f, 0.9f), PointF(0.2f, 0.9f))
        val turned = quad.rotate90CW()
        assertNear(0.1f, turned.topLeft.x)
        assertNear(0.2f, turned.topLeft.y)
        assertNear(0.7f, turned.topRight.x)
        assertNear(0.2f, turned.topRight.y)
    }

    @Test
    fun warpsTheRightHalfWithoutMixingTheLeftColor() {
        val pixels = IntArray(40 * 20)
        for (y in 0 until 20) {
            for (x in 0 until 40) {
                pixels[y * 40 + x] = if (x < 20) argb(255, 220, 0, 0) else argb(255, 0, 0, 220)
            }
        }
        val image = RgbaImage(40, 20, pixels)
        val quad = Quad(PointF(0.55f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0.55f, 1f))
        val warped = PerspectiveWarp.warp(image, quad)
        val sample = warped.pixel(warped.width * 3 / 4, warped.height / 2)
        assertTrue(sample.blue() > sample.red(), "right half should stay blue, got r=${sample.red()} b=${sample.blue()}")
    }

    @Test
    fun fullFrameSkipsWarp() {
        val image = RgbaImage.filled(12, 8, argb(255, 10, 20, 30))
        val warped = PerspectiveWarp.warp(image, Quad.full())
        assertEquals(image.width, warped.width)
        assertEquals(image.pixel(1, 1), warped.pixel(1, 1))
    }

    @Test
    fun filtersKeepSizeAndGrayIsNeutral() {
        val source = RgbaImage.filled(8, 6, argb(255, 180, 40, 20))
        FilterType.entries.forEach { filter ->
            val out = ImageFilters.apply(source, filter)
            assertEquals(source.width, out.width)
            assertEquals(source.height, out.height)
        }
        val gray = ImageFilters.apply(source, FilterType.Gray).pixel(0, 0)
        assertEquals(gray.red(), gray.green())
        assertEquals(gray.green(), gray.blue())
    }

    @Test
    fun pdfEmbedsJpegAndHasATrailer() {
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33, 0x44, 0xFF.toByte(), 0xD9.toByte())
        val pdf = PdfWriter.write(listOf(PdfImage(payload, 30, 40), PdfImage(payload, 10, 10)))
        assertTrue(pdf.startsWithAscii("%PDF-1.4"))
        assertTrue(pdf.hasAscii("/DCTDecode"))
        assertTrue(pdf.hasAscii("%%EOF"))
        assertTrue(pdf.indexOfSequence(payload) >= 0)
    }

    @Test
    fun repositoryStoresRenamesReordersAndExports() {
        val fileSystem = FakeFileSystem().apply { emulateUnix() }
        var now = 1_000L
        val repository = DocumentRepository("/docs", fileSystem) { now }
        val first = repository.create("合同", listOf(page(byteArrayOf(1, 2, 3), FilterType.Original)))
        now += 10
        val second = repository.create("合同", listOf(page(byteArrayOf(4, 5), FilterType.Gray), page(byteArrayOf(6), FilterType.Enhance)))
        assertEquals("合同 2", second.title)
        assertEquals(listOf(second.id, first.id), repository.list().map { it.id })

        val renamed = repository.rename(second.id, "发票")
        assertEquals("发票", renamed?.title)
        val moved = repository.movePage(second.id, second.pages[1].id, -1)
        assertEquals(second.pages[1].id, moved?.pages?.first()?.id)

        val pdf = repository.exportPdf(second.id)
        assertTrue(repository.readBytes(pdf).startsWithAscii("%PDF-1.4"))

        val afterDelete = repository.deletePage(first.id, first.pages.first().id)
        assertNull(afterDelete)
        assertEquals(1, repository.list().size)
        repository.delete(second.id)
        assertTrue(repository.list().isEmpty())
        assertNull(repository.load(second.id))
    }

    private fun page(bytes: ByteArray, filter: FilterType) = EncodedPage(bytes, 20, 30, filter)

    private fun fillRect(image: RgbaImage, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top until bottom) {
            val row = y * image.width
            for (x in left until right) image.pixels[row + x] = color
        }
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.08f, "expected $expected but was $actual")
    }
}

private fun ByteArray.startsWithAscii(text: String): Boolean {
    val prefix = text.encodeToByteArray()
    if (size < prefix.size) return false
    return prefix.indices.all { this[it] == prefix[it] }
}

private fun ByteArray.hasAscii(text: String): Boolean = indexOfSequence(text.encodeToByteArray()) >= 0

private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (start in 0..size - needle.size) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return start
    }
    return -1
}
