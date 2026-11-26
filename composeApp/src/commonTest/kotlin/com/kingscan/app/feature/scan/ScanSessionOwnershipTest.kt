package com.kingscan.app.feature.scan

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.imaging.*
import com.kingscan.app.core.document.*
import com.kingscan.app.core.pdf.PdfSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScanSessionOwnershipTest {
    @Test fun switchingSessionWaitsForWorkerBeforeDeletingItsInputOrResettingTarget() = runTest {
        val repository = DocumentRepository("/session", FakeFileSystem())
        val file = repository.stageImport(byteArrayOf(1), "source.pdf", true)
        val session = ScanSession(repository::finishImport, { PageEdit() })
        session.documentId = "oldtarget"
        session.activeImport = file
        val release = CompletableDeferred<Unit>()
        session.launchProcessing(this) {
            withContext(NonCancellable) { release.await() }
        }
        runCurrent()
        val transition = launch { session.beginAppend("newtarget") }
        runCurrent()
        try {
            assertFalse(transition.isCompleted, "Session reset must wait for the cancelled worker")
            assertEquals("oldtarget", session.documentId)
            assertContentEquals(byteArrayOf(1), repository.readBytes(file.path))
        } finally {
            release.complete(Unit)
            transition.join()
        }
        assertEquals("newtarget", session.documentId)
        assertFails { repository.readBytes(file.path) }
    }

    @Test fun renderInterleavingCannotPurgeAnExistingNewTarget() {
        val repository = DocumentRepository("/pdf-task", FakeFileSystem())
        val existing = repository.create("已有文档", listOf(page))
        val file = repository.stageImport(byteArrayOf(5), "new.pdf", true)
        val session = ScanSession(repository::finishImport, { PageEdit() })
        val source = object : PdfSource {
            override val pageCount = 1
            override fun renderPage(index: Int, maxEdge: Int): RgbaImage {
                session.documentId = existing.id
                repository.finishImport(file)
                return RgbaImage.filled(16, 16, -1)
            }
            override fun close() {}
        }
        assertFails { importPdf(repository, codec, session.documentId, file, openSource = { _, _ -> source }) }
        val remaining = assertNotNull(repository.load(existing.id), "Rollback may only purge a document created by this task")
        assertEquals(existing.pages, remaining.pages)
        assertContentEquals(page.jpeg, repository.readBytes(repository.pagePath(existing.id, remaining.pages.single())))
    }

    @Test fun cameraReentryKeepsFingerprintUntilPageChangesOrNewSessionStarts() = runTest {
        val session = ScanSession({}, { PageEdit() })
        val controller = session.captureController
        controller.markCaptured(frame(0))
        val returned = session.captureController
        for (time in 120L..1800L step 120) {
            assertFalse(returned.update(frame(time), true, false).capture, "Returning from editing must not capture the same page")
        }
        assertTrue((1920L..3840L step 120).any { returned.update(frame(it, changed = true), true, false).capture })
        session.beginNew()
        assertTrue((4000L..5800L step 120).any { session.captureController.update(frame(it), true, false).capture })
    }

    private fun frame(time: Long, changed: Boolean = false) = CaptureFrame(Quad.inset(0.2f), 360, 480,
        time, 180f, 100f, IntArray(768) { if (changed && it % 2 == 0) 100 else 180 })
    private val page = EncodedPage(byteArrayOf(1), 16, 16, FilterType.Original)
    private val codec = object : ImageCodec {
        override fun decode(bytes: ByteArray, maxEdge: Int) = RgbaImage.filled(16, 16, -1)
        override fun encodeJpeg(image: RgbaImage, quality: Int) = byteArrayOf(2)
    }
}
