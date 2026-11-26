package com.kingscan.app.feature.scan

import com.kingscan.app.core.storage.DocumentRepository

import com.kingscan.app.core.imaging.ImageCodec
import com.kingscan.app.core.imaging.ScanLimits
import com.kingscan.app.core.document.*
import com.kingscan.app.core.pdf.PdfSource
import com.kingscan.app.core.pdf.openPdfSource

internal data class ImportedPdf(val pages: SavedScanPages, val original: OriginalPdf) {
    fun rollback(repository: DocumentRepository) {
        if (!pages.created) repository.detachOriginalPdf(pages.document.id, original)
        pages.rollback(repository)
    }
}

internal fun importPdf(
    repository: DocumentRepository, codec: ImageCodec, targetId: String?,
    file: ImportFile, password: String = "", checkActive: () -> Unit = {},
    openSource: (String, String) -> PdfSource = ::openPdfSource,
): ImportedPdf {
    checkActive()
    val source = openSource(file.path, password)
    var destination = targetId
    var createdId: String? = null
    val addedIds = mutableListOf<String>()
    var original: OriginalPdf? = null
    try {
        try {
            require(source.pageCount > 0) { "PDF 没有页面" }
            for (index in 0 until source.pageCount) {
                checkActive()
                val image = source.renderPage(index, ScanLimits.MAX_EDGE)
                checkActive()
                val bytes = codec.encodeJpeg(image, ScanLimits.JPEG_QUALITY)
                checkActive()
                val page = EncodedPage(bytes, image.width, image.height, FilterType.Original,
                    originalBytes = bytes, edit = PageEdit(filter = FilterType.Original))
                val updated = if (destination == null) {
                    repository.create(file.name.substringBeforeLast('.'), listOf(page)).also { createdId = it.id }
                } else repository.addPages(destination, listOf(page))
                destination = updated.id
                addedIds += updated.pages.last().id
                checkActive()
            }
            original = repository.attachOriginalPdf(destination!!, file)
            checkActive()
        } finally { source.close() }
        return ImportedPdf(SavedScanPages(repository.load(destination!!)!!, createdId != null, addedIds), original!!)
    } catch (failure: Throwable) {
        try {
            if (createdId != null) {
                repository.rollbackOwnedPages(createdId, true, addedIds)
            } else if (targetId != null) {
                original?.let { repository.detachOriginalPdf(targetId, it) }
                if (addedIds.isNotEmpty()) repository.rollbackOwnedPages(targetId, false, addedIds)
            }
        } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
}
