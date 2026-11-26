package com.kingscan.app

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.feature.editor.PageOperations
import com.kingscan.app.feature.scan.ScanSession
import com.kingscan.app.core.imaging.ImageCodec
import com.kingscan.app.core.imaging.createImageCodec
import com.kingscan.app.core.document.ImportFile
import com.kingscan.app.core.document.PageEdit
import com.kingscan.app.core.document.PageMeta
import com.kingscan.app.core.document.ScanDocument
import com.kingscan.app.core.ocr.DocumentOcr
import com.kingscan.app.core.security.SecurityState
import kotlinx.coroutines.channels.Channel

object AppGraph {
    lateinit var repository: DocumentRepository
        private set
    lateinit var codec: ImageCodec
        private set
    lateinit var pageOperations: PageOperations
        private set
    lateinit var ocr: DocumentOcr
        private set
    lateinit var session: ScanSession
        private set
    val externalImports = Channel<ExternalImport>(Channel.UNLIMITED)
    val savedImages = Channel<List<String>>(Channel.UNLIMITED)
    val security = SecurityState()
    val deviceLockEnabled: Boolean get() = security.supported && security.appLockEnabled
    val locked: Boolean get() = security.appLocked

    internal fun configureDeviceLock(enabled: Boolean) {
        repository.saveDeviceLock(enabled)
        security.setAppLock(enabled)
    }

    fun lock() { security.background() }
    internal fun unlock() { security.unlockApp() }
    internal fun configureFolderLock(id: String, enabled: Boolean) {
        repository.saveFolderLock(id, enabled)
        security.setFolderLock(id, enabled)
    }
    internal fun unlockFolder(id: String) { security.unlockFolder(id) }

    private fun notifyScanSaved(document: ScanDocument, pages: List<PageMeta>) {
        if (repository.settings().autoSaveToGallery) {
            runCatching { pages.map { repository.pagePath(document.id, it) } }.onSuccess { savedImages.trySend(it) }
        }
    }

    fun init(storageRoot: String, biometricsSupported: Boolean = true) {
        if (::repository.isInitialized) return
        repository = DocumentRepository(storageRoot, security = security)
        codec = createImageCodec()
        pageOperations = PageOperations(repository, codec)
        ocr = DocumentOcr(repository, codec)
        session = ScanSession(repository::finishImport,
            { PageEdit(filter = repository.settings().defaultFilter) }, repository, codec, ::notifyScanSaved)
        security.initialize(biometricsSupported, repository.settings().deviceLock,
            repository.folders().filter { it.locked }.map { it.id }.toSet())
    }
}

data class ExternalImport(val files: List<ImportFile> = emptyList(), val error: String? = null)
