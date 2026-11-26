package com.kingscan.app.feature.scan

import com.kingscan.app.core.storage.DocumentRepository

import com.kingscan.app.core.document.EncodedPage
import com.kingscan.app.core.document.ScanDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class SavedScanPages(val document: ScanDocument, val created: Boolean, val addedIds: List<String>) {
    fun rollback(repository: DocumentRepository) {
        repository.rollbackOwnedPages(document.id, created, addedIds)
    }
}

internal fun saveScanPages(
    repository: DocumentRepository, targetId: String?, title: String, pages: List<EncodedPage>,
    editingId: String? = null,
): SavedScanPages {
    val document = when {
        targetId != null && editingId != null -> repository.replacePage(targetId, editingId, pages.single()) ?: error("页面不存在")
        targetId == null -> repository.create(title, pages)
        else -> repository.addPages(targetId, pages)
    }
    return SavedScanPages(document, targetId == null, if (editingId == null) document.pages.takeLast(pages.size).map { it.id } else emptyList())
}

/**
 * Keep the result deliverable even when cancellation races the return from native synchronous work.
 * The original job remains the cancellation authority; only cleanup and result delivery are shielded.
 */
internal suspend fun <T> writeAndPublish(
    work: (checkActive: () -> Unit) -> T, rollback: (T) -> Unit, publish: (T) -> Unit,
) {
    val owner = coroutineContext
    withContext(NonCancellable) {
        val result = withContext(Dispatchers.Default) { work { owner.ensureActive() } }
        try {
            owner.ensureActive()
        } catch (failure: Throwable) {
            try { withContext(Dispatchers.Default) { rollback(result) } }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        // No suspension between the cancellation fence and publishing committed state on the UI thread.
        publish(result)
    }
}
