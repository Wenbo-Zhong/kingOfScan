package com.kingscan.app

import com.kingscan.app.data.DocumentRepository
import com.kingscan.app.model.EncodedPage
import com.kingscan.app.model.FilterType
import com.kingscan.app.model.OcrResult
import com.kingscan.app.model.PageEdit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class DocumentLifecycleTest {
    private fun page(value: Int) = EncodedPage(
        jpeg = byteArrayOf(value.toByte()),
        width = 20, height = 30, filter = FilterType.Enhance,
        originalBytes = byteArrayOf(99, value.toByte()),
        edit = PageEdit(quarterTurns = 1),
        ocr = OcrResult("合同 $value"),
    )

    @Test
    fun oldMetadataLoadsWithoutInventingAnOriginal() {
        val fs = FakeFileSystem().apply { emulateUnix() }
        fs.createDirectories("/docs/documents/legacy/pages".toPath())
        fs.write("/docs/documents/legacy/meta.json".toPath()) {
            writeUtf8("""{"id":"legacy","title":"旧文档","createdAt":1,"updatedAt":2,"pages":[{"id":"p","fileName":"p.jpg","width":20,"height":30,"filter":"Gray"}]}""")
        }
        val repo = DocumentRepository("/docs", fs)
        val doc = assertNotNull(repo.load("legacy"))
        assertNull(doc.pages.first().originalFileName)
        assertNull(doc.pages.first().edit)
        assertEquals(emptyList(), doc.tags)
    }

    @Test
    fun mergingCopiesOriginalsAndOcrAndSurvivesPurgingSources() {
        val fs = FakeFileSystem().apply { emulateUnix() }
        val repo = DocumentRepository("/docs", fs)
        val a = repo.create("A", listOf(page(1)))
        val b = repo.create("B", listOf(page(2), page(3)))
        val merged = repo.merge(listOf(b.id, a.id), "合并")
        listOf(a, b).forEach { repo.delete(it.id); repo.purge(it.id) }
        assertEquals(listOf("合同 2", "合同 3", "合同 1"), merged.pages.map { it.ocr?.text })
        merged.pages.forEachIndexed { index, stored ->
            val value = listOf(2, 3, 1)[index]
            assertContentEquals(byteArrayOf(value.toByte()), repo.readBytes(repo.pagePath(merged.id, stored)))
            assertContentEquals(byteArrayOf(99, value.toByte()), repo.readBytes(assertNotNull(repo.originalPath(merged.id, stored))))
            assertEquals(1, stored.edit?.quarterTurns)
        }
    }

    @Test
    fun trashAndRestoreKeepOriginalBytesAndFolderAssociation() {
        val fs = FakeFileSystem().apply { emulateUnix() }
        val repo = DocumentRepository("/docs", fs)
        val folder = repo.createFolder("合同")
        val doc = repo.create("签字", listOf(page(1)))
        repo.organize(doc.id, folder.id, listOf("工作"), starred = true)
        repo.delete(doc.id)
        assertNull(repo.load(doc.id))
        assertTrue(repo.list().isEmpty())
        assertEquals(1, repo.list(includeTrash = true).size)
        val restored = assertNotNull(repo.restore(doc.id))
        assertEquals(folder.id, restored.folderId)
        assertTrue(restored.starred)
        assertContentEquals(byteArrayOf(99, 1), repo.readBytes(assertNotNull(repo.originalPath(doc.id, restored.pages.first()))))
        assertFailsWith<IllegalArgumentException> { repo.purge(doc.id) }
    }

    @Test
    fun invalidPagePermutationLeavesDocumentUnchanged() {
        val fs = FakeFileSystem().apply { emulateUnix() }
        val repo = DocumentRepository("/docs", fs)
        val doc = repo.create("排序", listOf(page(1), page(2)))
        assertFailsWith<IllegalArgumentException> { repo.reorderPages(doc.id, listOf(doc.pages[0].id, doc.pages[0].id)) }
        assertEquals(doc.pages, repo.load(doc.id)?.pages)
    }

    @Test
    fun failedMetadataCommitDoesNotDestroyThePreviouslySavedImage() {
        val fs = FakeFileSystem().apply { emulateUnix() }
        var failCommit = false
        val faulting = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                if (failCommit && target.name == "meta.json") throw IOException("injected metadata failure")
                super.atomicMove(source, target)
            }
        }
        val repo = DocumentRepository("/docs", faulting)
        val doc = repo.create("事务", listOf(page(1)))
        val before = fs.list("/docs/documents/${doc.id}/pages".toPath()).toSet()
        failCommit = true
        assertFailsWith<IOException> { repo.replacePage(doc.id, doc.pages.first().id, page(2)) }
        assertEquals(doc, repo.load(doc.id))
        assertEquals(before, fs.list("/docs/documents/${doc.id}/pages".toPath()).toSet())
        assertContentEquals(byteArrayOf(1), repo.readBytes(repo.pagePath(doc.id, doc.pages.first())))
    }
}
