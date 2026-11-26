package com.kingscan.app.feature.scan

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.imaging.ImageCodec
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.document.*
import com.kingscan.app.core.pdf.PdfSource
import kotlinx.coroutines.CancellationException
import okio.fakefilesystem.FakeFileSystem
import okio.Path.Companion.toPath
import kotlin.test.*

class PdfImportOwnershipTest {
    @Test fun successfulImportKeepsOriginalPdfAndClosesSource() {
        val repository = DocumentRepository("/pdf-success", FakeFileSystem())
        val file = repository.stageImport(byteArrayOf(5), "scan.pdf", true)
        val source = Source(2)
        val imported = importPdf(repository, codec, null, file, openSource = { _, _ -> source })
        val document = imported.pages.document
        assertTrue(source.closed)
        assertTrue(imported.pages.created)
        assertEquals(2, document.pages.size)
        assertEquals(listOf(imported.original), document.originalPdfs)
        assertContentEquals(byteArrayOf(5), repository.readBytes(repository.originalPdfPath(document.id, imported.original)))
    }

    @Test fun cancellationAfterRenderingCannotCommitAnyPages() {
        val repository = DocumentRepository("/pdf-cancel", FakeFileSystem())
        val existing = repository.create("已有文档", listOf(page))
        val file = repository.stageImport(byteArrayOf(5), "scan.pdf", true)
        var cancelled = false
        val source = Source(2) { cancelled = true }
        assertFailsWith<CancellationException> {
            importPdf(repository, codec, existing.id, file,
                checkActive = { if (cancelled) throw CancellationException("cancelled during render") },
                openSource = { _, _ -> source })
        }
        assertTrue(source.closed)
        assertEquals(existing, repository.load(existing.id))
    }

    @Test fun laterRenderFailureRollsBackOnlyThisFilesPages() {
        val repository = DocumentRepository("/pdf-partial", FakeFileSystem())
        val existing = repository.create("已有文档", listOf(page))
        val firstFile = repository.stageImport(byteArrayOf(4), "earlier.pdf", true)
        val previousPdf = repository.attachOriginalPdf(existing.id, firstFile)
        val before = repository.load(existing.id)!!
        val file = repository.stageImport(byteArrayOf(5), "failed.pdf", true)
        val source = Source(2) { if (it == 1) error("render failed") }
        assertFailsWith<IllegalStateException> {
            importPdf(repository, codec, existing.id, file, openSource = { _, _ -> source })
        }
        assertTrue(source.closed)
        val after = repository.load(existing.id)!!
        assertEquals(before.pages, after.pages)
        assertEquals(before.originalPdfs, after.originalPdfs)
        assertContentEquals(byteArrayOf(4), repository.readBytes(repository.originalPdfPath(existing.id, previousPdf)))
    }

    @Test fun cancellationAfterAttachingOriginalRemovesOnlyOwnedSourceAndPages() {
        val fs = FakeFileSystem()
        val repository = DocumentRepository("/pdf-attached", fs)
        val existing = repository.create("已有文档", listOf(page))
        val earlier = repository.attachOriginalPdf(existing.id, repository.stageImport(byteArrayOf(4), "earlier.pdf", true))
        val before = repository.load(existing.id)!!
        val file = repository.stageImport(byteArrayOf(5), "new.pdf", true)
        val source = Source(1)
        assertFailsWith<CancellationException> {
            importPdf(repository, codec, existing.id, file, checkActive = {
                if (repository.load(existing.id)!!.originalPdfs.size > before.originalPdfs.size) {
                    throw CancellationException("cancelled after attachment")
                }
            }, openSource = { _, _ -> source })
        }
        val after = repository.load(existing.id)!!
        assertEquals(before.pages, after.pages)
        assertEquals(listOf(earlier), after.originalPdfs)
        assertEquals(listOf(earlier.fileName), fs.list("/pdf-attached/documents/${existing.id}/sources".toPath()).map { it.name })
        assertTrue(source.closed)
    }

    @Test fun emptyPdfClosesSourceAndLeavesNoDocument() {
        val repository = DocumentRepository("/pdf-empty", FakeFileSystem())
        val source = Source(0)
        val file = repository.stageImport(byteArrayOf(5), "empty.pdf", true)
        assertFailsWith<IllegalArgumentException> {
            importPdf(repository, codec, null, file, openSource = { _, _ -> source })
        }
        assertTrue(source.closed)
        assertTrue(repository.list(includeTrash = true).isEmpty())
    }

    private class Source(override val pageCount: Int, private val render: (Int) -> Unit = {}) : PdfSource {
        var closed = false
        override fun renderPage(index: Int, maxEdge: Int): RgbaImage {
            render(index)
            return RgbaImage.filled(16, 16, -1)
        }
        override fun close() { closed = true }
    }
    private val page = EncodedPage(byteArrayOf(1), 16, 16, FilterType.Original)
    private val codec = object : ImageCodec {
        override fun decode(bytes: ByteArray, maxEdge: Int) = RgbaImage.filled(16, 16, -1)
        override fun encodeJpeg(image: RgbaImage, quality: Int) = byteArrayOf(2)
    }
}
