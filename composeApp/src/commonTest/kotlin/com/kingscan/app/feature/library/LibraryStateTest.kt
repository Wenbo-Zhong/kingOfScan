package com.kingscan.app.feature.library

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.document.*
import com.kingscan.app.core.security.SecurityState
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class LibraryStateTest {
    private val security = SecurityState().also { it.initialize(true, false, emptySet()) }
    private val repository = DocumentRepository("/library-state", FakeFileSystem(), security)
    private val state = LibraryState(repository)
    private val page = EncodedPage(byteArrayOf(1), 16, 16, FilterType.Original)

    @Test fun filtersAndOrderedSelectionSurviveBatchSnapshot() = runTest {
        val first = repository.create("B", listOf(page))
        val second = repository.create("A", listOf(page, page))
        repository.organize(first.id, starred = true)
        state.starredOnly = true
        assertEquals(listOf(first.id), state.documents().map { it.id })
        state.starredOnly = false
        state.sort = 1
        assertEquals(listOf(second.id, first.id), state.documents().map { it.id })
        state.selected = listOf(first.id, second.id)
        state.batch({ ids ->
            state.selected = emptyList()
            assertEquals(listOf(first.id, second.id), ids)
        }, { fail(it) })
        assertFalse(state.importing)
        assertEquals(1, state.revision)
    }

    @Test fun revokedFolderAccessClosesSensitiveSelectionDialogsAndKeepsDraft() {
        val folder = repository.createFolder("locked")
        state.folderId = folder.id
        state.selected = listOf("selection")
        state.selecting = true
        state.moveDialog = true
        state.purgeDialog = true
        state.folderDraft = "draft"
        security.setFolderLock(folder.id, true)
        state.securityChanged()
        assertNull(state.folderId)
        assertTrue(state.selected.isEmpty())
        assertFalse(state.selecting || state.moveDialog || state.purgeDialog)
        assertEquals("draft", state.folderDraft)
    }
}
