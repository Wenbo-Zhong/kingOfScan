package com.kingscan.app.feature.scan

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.document.EncodedPage
import com.kingscan.app.core.document.FilterType
import com.kingscan.app.core.imaging.ImageCodec
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.pdf.PdfSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class ScanWriteCancellationTest {
    @Test fun cancellationAfterCaptureCommitBeforeUiDeliveryRollsBackOwnedPages() = runTest {
        val repository = DocumentRepository("/capture-delivery", FakeFileSystem())
        val existing = repository.create("保留文档", listOf(page))
        for (target in listOf(null, existing.id)) {
            var published = false
            val job = launch {
                val owner = coroutineContext[Job]!!
                writeAndPublish(
                    work = { checkActive ->
                        checkActive()
                        saveScanPages(repository, target, "新拍摄", listOf(page)).also { owner.cancel() }
                    },
                    rollback = { it.rollback(repository) },
                    publish = { published = true },
                )
            }
            job.join()
            assertFalse(published)
            assertEquals(listOf(existing.id), repository.list(includeTrash = true).map { it.id })
            assertEquals(existing.pages, repository.load(existing.id)!!.pages)
            assertContentEquals(page.jpeg, repository.readBytes(repository.pagePath(existing.id, existing.pages.single())))
        }
    }

    @Test fun activeCapturePublishesCommittedDocument() = runTest {
        val repository = DocumentRepository("/capture-active", FakeFileSystem())
        var documentId: String? = null
        writeAndPublish(
            work = { checkActive ->
                checkActive()
                saveScanPages(repository, null, "拍摄", listOf(page))
            },
            rollback = { it.rollback(repository) },
            publish = { documentId = it.document.id },
        )
        assertEquals(1, repository.load(assertNotNull(documentId))!!.pages.size)
    }

    @Test fun cancelledPdfResultDeliveryRollsBackItsOriginalAndAppendedPages() = runTest {
        val repository = DocumentRepository("/pdf-delivery", FakeFileSystem())
        val existing = repository.create("保留文档", listOf(page))
        val file = repository.stageImport(byteArrayOf(5), "scan.pdf", true)
        var published = false
        val job = launch {
            val owner = coroutineContext[Job]!!
            val source = object : PdfSource {
                override val pageCount = 1
                override fun renderPage(index: Int, maxEdge: Int) = RgbaImage.filled(16, 16, -1)
                override fun close() { owner.cancel() }
            }
            val codec = object : ImageCodec {
                override fun decode(bytes: ByteArray, maxEdge: Int) = RgbaImage.filled(16, 16, -1)
                override fun encodeJpeg(image: RgbaImage, quality: Int) = byteArrayOf(2)
            }
            writeAndPublish(
                work = { checkActive -> importPdf(repository, codec, existing.id, file, checkActive = checkActive,
                    openSource = { _, _ -> source }) },
                rollback = { it.rollback(repository) },
                publish = { published = true },
            )
        }
        job.join()
        assertFalse(published)
        assertEquals(existing.pages, repository.load(existing.id)!!.pages)
        assertTrue(repository.load(existing.id)!!.originalPdfs.isEmpty())
    }

    private val page = EncodedPage(byteArrayOf(1), 16, 16, FilterType.Original)
}
