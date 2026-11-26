package com.kingscan.app.core.ocr

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.imaging.*
import com.kingscan.app.core.document.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class DocumentOcrTest {
    private val repository = DocumentRepository("/ocr-service", FakeFileSystem())
    private val image = RgbaImage.filled(16, 16, -1)
    private val codec = object : ImageCodec {
        override fun decode(bytes: ByteArray, maxEdge: Int) = image
        override fun encodeJpeg(image: RgbaImage, quality: Int) = byteArrayOf(1)
    }
    private fun document() = repository.create("OCR", List(3) { EncodedPage(byteArrayOf(1), 16, 16, FilterType.Original) })
    private fun engine(block: suspend (OcrLanguage) -> OcrResult) = object : OcrEngine {
        override suspend fun recognize(image: RgbaImage, language: OcrLanguage) = block(language)
    }

    @Test fun singlePageAndMissingSelectionPreserveExistingRecognition() = runTest {
        val document = document()
        var calls = 0
        val service = DocumentOcr(repository, codec) {
            engine { assertEquals(OcrLanguage.Latin, it); OcrResult("text-${++calls}") }
        }
        assertEquals(1, service.recognize(document.id, OcrLanguage.Latin, document.pages[1].id))
        assertNull(repository.load(document.id)!!.pages[0].ocr)
        assertEquals(2, service.recognize(document.id, OcrLanguage.Latin, selection = OcrSelection.Missing))
        assertEquals("text-1", repository.load(document.id)!!.pages[1].ocr!!.text)
        assertEquals(0, service.recognize(document.id, OcrLanguage.Latin, selection = OcrSelection.Missing))
        assertEquals(3, calls)
    }

    @Test fun failureLeavesCompletedPagesAndReportsOnlyPersistedResults() = runTest {
        val document = document()
        var calls = 0
        val saved = mutableListOf<String>()
        val service = DocumentOcr(repository, codec) {
            engine { if (++calls == 2) error("failed"); OcrResult("first") }
        }
        assertFailsWith<IllegalStateException> {
            service.recognize(document.id, OcrLanguage.Chinese, onSaved = { page, _ -> saved += page.id })
        }
        assertEquals(listOf(document.pages[0].id), saved)
        assertEquals(listOf("first", null, null), repository.load(document.id)!!.pages.map { it.ocr?.text })
    }

    @Test fun cancellationDuringNativeRecognitionCannotPublishLateResult() = runTest {
        val document = document()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = DocumentOcr(repository, codec) {
            engine {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                OcrResult("late")
            }
        }
        val job = launch { service.recognize(document.id, OcrLanguage.Chinese) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(repository.load(document.id)!!.pages.all { it.ocr == null })
    }
}
