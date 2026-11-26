package com.kingscan.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.document.ScanDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ordered selection and library filters are independent of the native skin. */
class LibraryState(private val repository: DocumentRepository) {
    var query by mutableStateOf("")
    var revision by mutableIntStateOf(0)
    var showAbout by mutableStateOf(false)
    var showMenu by mutableStateOf(false)
    var importing by mutableStateOf(false)
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf<List<String>>(emptyList())
    var folderId by mutableStateOf<String?>(null)
    var starredOnly by mutableStateOf(false)
    var trash by mutableStateOf(false)
    var folderDraft by mutableStateOf("")
    var folderDialog by mutableStateOf(false)
    var moveDialog by mutableStateOf(false)
    var purgeDialog by mutableStateOf(false)
    var sort by mutableIntStateOf(0)

    fun documents(): List<ScanDocument> = repository.search(query, includeTrash = trash).filter {
        (it.deletedAt != null) == trash && (folderId == null || it.folderId == folderId) && (!starredOnly || it.starred)
    }.let { list -> when (sort) {
        1 -> list.sortedBy { it.title }
        2 -> list.sortedByDescending { it.pages.size }
        else -> list
    } }

    fun refresh() { revision++; selected = emptyList(); selecting = false }

    fun securityChanged() {
        selected = emptyList()
        selecting = false
        moveDialog = false
        purgeDialog = false
        if (!repository.canAccessFolder(folderId)) folderId = null
    }

    suspend fun batch(action: (List<String>) -> Unit, onMessage: (String) -> Unit) {
        val snapshot = selected.toList()
        importing = true
        try {
            withContext(Dispatchers.Default) { action(snapshot) }
            refresh()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { onMessage(failure.message ?: "操作失败") }
        finally { importing = false }
    }
}
