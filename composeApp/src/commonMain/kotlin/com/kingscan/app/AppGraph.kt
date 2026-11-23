package com.kingscan.app

import com.kingscan.app.data.DocumentRepository
import com.kingscan.app.imaging.ImageCodec
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.imaging.createImageCodec
import com.kingscan.app.model.Quad
import com.kingscan.app.model.PageEdit
import com.kingscan.app.model.PageMeta
import com.kingscan.app.model.ImportFile
import com.kingscan.app.model.ScanMode
import com.kingscan.app.model.ScanDocument
import com.kingscan.app.imaging.ScanLimits
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

object AppGraph {
    lateinit var repository: DocumentRepository
        private set
    lateinit var codec: ImageCodec
        private set
    val session = ScanSession()
    val externalImports = Channel<ExternalImport>(Channel.UNLIMITED)
    val savedImages = Channel<List<String>>(Channel.UNLIMITED)
    var deviceLockEnabled by mutableStateOf(false)
        private set
    var locked by mutableStateOf(false)
        private set

    fun configureDeviceLock(enabled: Boolean) {
        deviceLockEnabled = enabled
        locked = false // Called only after system authentication, when saving settings.
    }

    fun lock() { if (deviceLockEnabled) locked = true }
    fun unlock() { locked = false } // Only the native authentication success callback calls this.

    fun notifyScanSaved(document: ScanDocument, pages: List<PageMeta>) {
        if (repository.settings().autoSaveToGallery) savedImages.trySend(pages.map { repository.pagePath(document.id, it) })
    }

    fun init(storageRoot: String) {
        if (::repository.isInitialized) return
        repository = DocumentRepository(storageRoot)
        codec = createImageCodec()
        deviceLockEnabled = repository.settings().deviceLock
        locked = deviceLockEnabled
    }
}

data class ExternalImport(val files: List<ImportFile> = emptyList(), val error: String? = null)

class ScanSession {
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
    var activeImport: ImportFile? = null
    var scanMode: ScanMode = ScanMode.Document
    var capturedPages: Int = 0
    val idCardPageIds = mutableListOf<String>()
    val largeFormatPageIds = mutableListOf<String>()

    fun beginNew() {
        documentId = null
        capturedPages = 0
        idCardPageIds.clear()
        largeFormatPageIds.clear()
        discardImports()
        clearImage()
    }

    fun beginAppend(documentId: String) {
        this.documentId = documentId
        capturedPages = 0
        idCardPageIds.clear()
        largeFormatPageIds.clear()
        discardImports()
        clearImage()
    }

    fun beginEdit(documentId: String, page: PageMeta) {
        val original = AppGraph.repository.originalPath(documentId, page)
            ?: error("这张旧页面没有保留原图，无法重新裁切。可重新导入原图。")
        beginAppend(documentId)
        editingPageId = page.id
        originalBytes = AppGraph.repository.readBytes(original)
        edit = page.edit ?: PageEdit(filter = page.filter)
        quarterTurns = edit.quarterTurns.mod(4)
        var decoded = AppGraph.codec.decode(originalBytes!!, ScanLimits.MAX_EDGE)
        repeat(quarterTurns) { decoded = decoded.rotate90CW() }
        source = decoded
        quad = edit.quad
    }

    fun clearImage() {
        editingPageId = null
        originalBytes = null
        quarterTurns = 0
        edit = PageEdit(filter = AppGraph.repository.settings().defaultFilter)
        source = null
        warped = null
        quad = Quad.inset(0.06f)
    }

    fun cancel() {
        beginNew()
    }

    private fun discardImports() {
        processingJob?.cancel()
        processingJob = null
        activeImport?.let(AppGraph.repository::finishImport)
        activeImport = null
        pendingImports.forEach(AppGraph.repository::finishImport)
        pendingImports.clear()
    }
}
