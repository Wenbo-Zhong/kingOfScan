package com.kingscan.app.feature.scan

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.imaging.ImageCodec
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.document.Quad
import com.kingscan.app.core.document.PageEdit
import com.kingscan.app.core.document.PageMeta
import com.kingscan.app.core.document.ImportFile
import com.kingscan.app.core.document.ScanMode
import com.kingscan.app.core.document.ScanDocument
import com.kingscan.app.core.imaging.ScanLimits
import com.kingscan.app.core.imaging.AutoCaptureController
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

class ScanSession(
    private val finishImport: (ImportFile) -> Unit,
    private val defaultEdit: () -> PageEdit,
    private val repository: DocumentRepository? = null,
    private val codec: ImageCodec? = null,
    private val onSaved: (ScanDocument, List<PageMeta>) -> Unit = { _, _ -> },
) {
    val captureController = AutoCaptureController()
    private val transitionMutex = Mutex()
    private val ownedJobs = mutableSetOf<Job>()
    var transitioning by mutableStateOf(false)
        private set
    var processing by mutableStateOf(false)
        private set
    var generation: Long = 0
        private set
    var documentId: String? = null
    var editingPageId: String? = null
    var originalBytes: ByteArray? = null
    var quarterTurns: Int = 0
    var edit: PageEdit = PageEdit()
    var source: RgbaImage? = null
    var quad: Quad = Quad.inset(0.06f)
    var warped: RgbaImage? = null
    val pendingImports = ArrayDeque<ImportFile>()
    var processingJob: Job? = null
        private set
    var activeImport: ImportFile? = null
    var scanMode: ScanMode = ScanMode.Document
    var capturedPages: Int = 0
    val idCardPageIds = mutableListOf<String>()
    val largeFormatPageIds = mutableListOf<String>()

    suspend fun beginNew() = transition(null)

    suspend fun beginAppend(documentId: String) = transition(documentId)

    suspend fun beginEdit(documentId: String, page: PageMeta) {
        val repository = checkNotNull(repository)
        val codec = checkNotNull(codec)
        val original = repository.originalPath(documentId, page)
            ?: error("这张旧页面没有保留原图，无法重新裁切。可重新导入原图。")
        beginAppend(documentId)
        var failure: Throwable? = null
        launchProcessing(CoroutineScope(coroutineContext)) {
            try {
                val pageEdit = page.edit ?: PageEdit(filter = page.filter)
                val turns = pageEdit.quarterTurns.mod(4)
                val prepared = withContext(Dispatchers.Default) {
                    val bytes = repository.readBytes(original)
                    var decoded = codec.decode(bytes, ScanLimits.MAX_EDGE)
                    repeat(turns) { decoded = decoded.rotate90CW() }
                    bytes to decoded
                }
                coroutineContext.ensureActive()
                editingPageId = page.id
                originalBytes = prepared.first
                edit = pageEdit
                quarterTurns = turns
                source = prepared.second
                quad = pageEdit.quad
            } catch (caught: Throwable) { failure = caught }
        }.join()
        coroutineContext.ensureActive()
        failure?.let { throw it }
        check(editingPageId == page.id) { "编辑已取消" }
    }

    fun clearImage() {
        editingPageId = null
        originalBytes = null
        quarterTurns = 0
        edit = defaultEdit()
        source = null
        warped = null
        quad = Quad.inset(0.06f)
    }

    /** Publish exactly the pages owned by the completed write; editing adds no capture. */
    internal fun accept(result: SavedScanPages, mode: ScanMode) {
        documentId = result.document.id
        val pages = result.document.pages.filter { it.id in result.addedIds }
        capturedPages += pages.size
        if (mode == ScanMode.IdCard) idCardPageIds += pages.map { it.id }
        if (mode == ScanMode.LargeFormat) largeFormatPageIds += pages.map { it.id }
        if (pages.isNotEmpty()) onSaved(result.document, pages)
    }

    suspend fun cancel() = beginNew()

    /** Stop background publication without discarding retained unsaved editor state. */
    fun interruptProcessing() { ownedJobs.toList().forEach { it.cancel() } }

    /** UI-thread entry points: transitions hold the target and inputs until owned work finishes. */
    private suspend fun transition(targetId: String?) = transitionMutex.withLock {
        withContext(NonCancellable) {
            transitioning = true
            try {
                val jobs = ownedJobs.toList()
                jobs.forEach { it.cancel() }
                jobs.forEach { it.join() }
                ownedJobs.clear()
                processingJob = null
                processing = false
                activeImport?.let(finishImport)
                activeImport = null
                pendingImports.forEach(finishImport)
                pendingImports.clear()
                documentId = targetId
                capturedPages = 0
                idCardPageIds.clear()
                largeFormatPageIds.clear()
                captureController.reset()
                clearImage()
                generation++
            } finally { transitioning = false }
        }
    }

    /** A handoff may replace the current job only from that job's final publication. */
    fun launchProcessing(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job {
        scope.coroutineContext.ensureActive()
        if (transitioning) throw CancellationException("扫描会话正在切换")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            finally {
                ownedJobs.remove(coroutineContext[Job])
                if (processingJob == coroutineContext[Job]) {
                    processingJob = null
                }
                processing = ownedJobs.isNotEmpty()
            }
        }
        processingJob = job
        ownedJobs += job
        processing = true
        job.start()
        return job
    }
}
