package com.kingscan.app.core.ocr

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.imaging.ImageCodec
import com.kingscan.app.core.imaging.ScanLimits
import com.kingscan.app.core.document.OcrResult
import com.kingscan.app.core.document.PageMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class OcrSelection { All, Missing }

class DocumentOcr(
    private val repository: DocumentRepository,
    private val codec: ImageCodec,
    private val createEngine: () -> OcrEngine = ::createOcrEngine,
) {
    suspend fun recognize(
        documentId: String, language: OcrLanguage, pageId: String? = null,
        selection: OcrSelection = OcrSelection.All,
        onStart: (PageMeta, Int) -> Unit = { _, _ -> },
        onSaved: (PageMeta, OcrResult) -> Unit = { _, _ -> },
    ): Int {
        val document = repository.load(documentId) ?: error("文档不存在")
        val pages = if (pageId == null) document.pages else listOf(
            document.pages.firstOrNull { it.id == pageId } ?: error("页面不存在"))
        val targets = pages.filter { selection != OcrSelection.Missing || it.ocr == null }
        if (targets.isEmpty()) return 0
        val engine = createEngine()
        targets.forEachIndexed { index, page ->
            coroutineContext.ensureActive()
            onStart(page, index)
            val image = withContext(Dispatchers.Default) {
                codec.decode(repository.readBytes(repository.pagePath(documentId, page)), ScanLimits.MAX_EDGE)
            }
            val result = engine.recognize(image, language)
            coroutineContext.ensureActive()
            repository.setOcr(documentId, page.id, result) ?: error("页面不存在")
            onSaved(page, result)
        }
        return targets.size
    }
}
