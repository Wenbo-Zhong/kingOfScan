package com.kingscan.app.feature.scan

import com.kingscan.app.core.storage.*
import com.kingscan.app.core.document.*
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class ScanPublicationTest {
    private val repository = DocumentRepository("/publication", FakeFileSystem())
    private val page = EncodedPage(byteArrayOf(1), 16, 16, FilterType.Original)

    @Test fun bookAddsBothPagesAndEditingDoesNotIncrementOrNotify() {
        val notifications = mutableListOf<List<String>>()
        val session = ScanSession({}, { PageEdit() }, onSaved = { _, pages -> notifications += pages.map { it.id } })
        val result = saveScanPages(repository, null, "book", listOf(page, page))
        session.accept(result, ScanMode.Book)
        val edit = saveScanPages(repository, result.document.id, "book", listOf(page), result.addedIds[0])
        session.accept(edit, ScanMode.Book)
        assertEquals(result.document.id, session.documentId)
        assertEquals(2, session.capturedPages)
        assertEquals(listOf(result.addedIds), notifications)
    }

    @Test fun modeListsContainOnlyOwnedPages() {
        val existing = repository.create("existing", listOf(page))
        val session = ScanSession({}, { PageEdit() })
        val card = saveScanPages(repository, existing.id, "", listOf(page))
        session.accept(card, ScanMode.IdCard)
        val large = saveScanPages(repository, existing.id, "", listOf(page))
        session.accept(large, ScanMode.LargeFormat)
        assertEquals(card.addedIds, session.idCardPageIds)
        assertEquals(large.addedIds, session.largeFormatPageIds)
        assertEquals(2, session.capturedPages)
    }
}
