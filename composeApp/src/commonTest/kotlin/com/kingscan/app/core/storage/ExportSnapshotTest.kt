package com.kingscan.app.core.storage

import com.kingscan.app.core.document.EncodedPage
import com.kingscan.app.core.document.FilterType
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class ExportSnapshotTest {
    @Test fun reexportAndPageEditsPreservePreviouslySharedPdf() {
        val repository = DocumentRepository("/snapshot", FakeFileSystem())
        val document = repository.create("合同", listOf(page(1)))
        val shared = repository.exportPdf(document.id)
        val original = repository.readBytes(shared)
        repository.addPages(document.id, listOf(page(2)))
        val next = repository.exportPdf(document.id)
        assertNotEquals(shared, next, "Each export must own a distinct snapshot path")
        assertContentEquals(original, repository.readBytes(shared))
        repository.replacePage(document.id, document.pages.single().id, page(3))
        assertContentEquals(original, repository.readBytes(shared))
    }

    private fun page(value: Int) = EncodedPage(byteArrayOf(value.toByte()), 20, 30, FilterType.Original)
}
