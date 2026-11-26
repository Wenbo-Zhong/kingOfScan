package com.kingscan.app.feature.editor

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.imaging.ImageCodec
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.document.*
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.IOException
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class AnnotationSaveTest {
    @Test fun retryAfterSecondPageFailureUsesCurrentBasesAndAppliesEachWatermarkOnce() {
        val fs = FailMetadataFileSystem(FakeFileSystem())
        val repository = DocumentRepository("/annotations", fs)
        val document = repository.create("批注", listOf(page(1), page(2)))
        document.pages.forEach { repository.setOcr(document.id, it.id, OcrResult("旧文字", emptyList())) }
        val watermark = PageMark("watermark", MarkType.Text, listOf(PointF(0.5f, 0.5f)), text = "仅供办理")
        fs.failAfter = 2
        var completed = 0
        assertFailsWith<IOException> {
            saveAnnotations(repository, codec, document, document.pages.first(), listOf(watermark), true,
                render = { image, _ -> image }) { completed++ }
        }
        assertEquals(1, completed)
        saveAnnotations(repository, codec, document, document.pages.first(), listOf(watermark), true,
            render = { image, _ -> image })
        val saved = repository.load(document.id)!!
        saved.pages.forEachIndexed { index, page ->
            assertEquals(listOf(watermark), page.edit!!.marks)
            assertNull(page.ocr)
            assertContentEquals(byteArrayOf((index + 1).toByte()), repository.readBytes(repository.annotationBasePath(saved.id, page)))
            assertContentEquals(byteArrayOf(9), repository.readBytes(repository.originalPath(saved.id, page)!!))
        }
    }

    @Test fun retryDoesNotDuplicateWatermarkOnAnAlreadySavedOtherPage() {
        val fs = FailMetadataFileSystem(FakeFileSystem())
        val repository = DocumentRepository("/three-page-annotations", fs)
        val pen = PageMark("existing-pen", MarkType.Pen, listOf(PointF(0.1f, 0.1f), PointF(0.2f, 0.2f)))
        val watermark = PageMark("watermark", MarkType.Text, listOf(PointF(0.5f, 0.5f)), text = "仅供办理")
        val document = repository.create("三页批注", listOf(page(1),
            page(2).copy(edit = PageEdit(filter = FilterType.Original, marks = listOf(pen))), page(3)))
        fs.failAfter = 3
        var completed = 0
        assertFailsWith<IOException> {
            saveAnnotations(repository, codec, document, document.pages.first(), listOf(watermark), true,
                render = { image, _ -> image }) { completed++ }
        }
        assertEquals(2, completed)
        saveAnnotations(repository, codec, document, document.pages.first(), listOf(watermark), true,
            render = { image, _ -> image })
        val saved = repository.load(document.id)!!
        assertEquals(listOf(pen, watermark), saved.pages[1].edit!!.marks)
        assertEquals(listOf(watermark), saved.pages[0].edit!!.marks)
        assertEquals(listOf(watermark), saved.pages[2].edit!!.marks)
    }

    private fun page(value: Int) = EncodedPage(byteArrayOf(value.toByte()), 32, 32, FilterType.Original,
        originalBytes = byteArrayOf(9), edit = PageEdit(filter = FilterType.Original))

    private val codec = object : ImageCodec {
        override fun decode(bytes: ByteArray, maxEdge: Int) = RgbaImage.filled(32, 32, 0xFFFFFFFF.toInt())
        override fun encodeJpeg(image: RgbaImage, quality: Int) = byteArrayOf(7)
    }

    private class FailMetadataFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        var failAfter: Int? = null
        override fun atomicMove(source: Path, target: Path) {
            if (target.name == "meta.json" && failAfter != null) {
                failAfter = failAfter!! - 1
                if (failAfter == 0) { failAfter = null; throw IOException("second page metadata failure") }
            }
            super.atomicMove(source, target)
        }
    }
}
