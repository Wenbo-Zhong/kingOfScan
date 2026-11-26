package com.kingscan.app.core.storage

import com.kingscan.app.feature.scan.saveScanPages
import com.kingscan.app.core.document.*
import com.kingscan.app.core.security.DocumentLockedException
import com.kingscan.app.core.security.SecurityState
import kotlin.test.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class FolderLockRepositoryTest {
    private val fs = FakeFileSystem().apply { emulateUnix() }
    private val security = SecurityState().apply { initialize(true, false, emptySet()) }
    private val repo = DocumentRepository("/docs", fs, security = security)
    private fun page(value: Int) = EncodedPage(byteArrayOf(value.toByte()), 20, 30, FilterType.Original,
        originalBytes = byteArrayOf(value.toByte()), ocr = OcrResult("秘密合同"))
    private fun lock(id: String) { repo.saveFolderLock(id, true); security.setFolderLock(id, true) }
    private fun unlock(id: String) {
        security.completeAuthentication(security.beginAuthentication(), true) { security.unlockFolder(id) }
    }

    @Test
    fun lockedFolderHidesTitlesOcrFavoritesAndTrashButKeepsUnprotectedDocumentsVisible() {
        val folder = repo.createFolder("私人")
        val document = repo.create("秘密合同", listOf(page(1)))
        repo.organize(document.id, folder.id, starred = true)
        val trashed = repo.create("秘密回收站", listOf(page(2)))
        repo.organize(trashed.id, folder.id)
        repo.delete(trashed.id)
        val public = repo.create("公开", listOf(page(3).copy(ocr = OcrResult("公开资料"))))
        lock(folder.id)
        assertEquals(listOf(public.id), repo.list(true).map { it.id })
        assertTrue(repo.search("秘密", true).isEmpty())
        assertNull(repo.load(document.id))
        assertNull(repo.load(trashed.id, true))
        assertEquals(folder.id, repo.blockedFolderForDocument(document.id))
        unlock(folder.id)
        assertNotNull(repo.load(document.id))
        assertNotNull(repo.load(trashed.id, true))
        assertEquals(2, repo.search("秘密", true).size)
    }

    @Test
    fun retainedPathsAndExportsCannotBypassRelocking() {
        val folder = repo.createFolder("私人")
        val document = repo.create("合同", listOf(page(1)))
        repo.organize(document.id, folder.id)
        val path = repo.pagePath(document.id, document.pages.single())
        val source = assertNotNull(repo.originalPath(document.id, document.pages.single()))
        val pdf = repo.exportPdf(document.id)
        val text = repo.exportText(document.id)
        val snapshot = repo.prepareOutput(path)
        lock(folder.id)
        listOf(path, source, pdf, text, snapshot).forEach {
            assertFailsWith<DocumentLockedException> { repo.readBytes(it) }
            assertFailsWith<DocumentLockedException> { repo.prepareOutput(it) }
        }
        assertFailsWith<DocumentLockedException> { repo.pagePath(document.id, document.pages.single()) }
        assertFailsWith<IllegalStateException> { repo.exportPdf(document.id) }
        unlock(folder.id)
        assertContentEquals(byteArrayOf(1), repo.readBytes(path))
        security.background()
        assertFailsWith<DocumentLockedException> { repo.readBytes(path) }
    }

    @Test
    fun movingIntoLockedFolderAndDeletingItsAssociationAreBlocked() {
        val folder = repo.createFolder("私人")
        val private = repo.create("私人合同", listOf(page(1)))
        repo.organize(private.id, folder.id)
        val public = repo.create("公开", listOf(page(2)))
        lock(folder.id)
        assertFailsWith<DocumentLockedException> { repo.organize(public.id, folder.id) }
        assertFailsWith<DocumentLockedException> { repo.deleteFolder(folder.id) }
        repo.delete(private.id)
        unlock(folder.id)
        assertNotNull(repo.load(private.id))
        assertFailsWith<IllegalArgumentException> { repo.deleteFolder(folder.id) }
        assertNull(repo.load(public.id)?.folderId)
        repo.organize(public.id, folder.id)
        security.background()
        assertNull(repo.load(public.id))
    }

    @Test
    fun ordinarySettingsSaveCannotChangeLockPolicy() {
        assertFailsWith<IllegalArgumentException> { repo.saveSettings(repo.settings().copy(deviceLock = true)) }
        repo.saveSettings(repo.settings().copy(autoCapture = true))
        assertTrue(repo.settings().autoCapture)
        assertFalse(repo.settings().deviceLock)
    }

    @Test
    fun appendAndSourceRollbackStillCleanOnlyOwnedFilesAfterAccessIsRevoked() {
        val folder = repo.createFolder("私人")
        val document = repo.create("合同", listOf(page(1)))
        repo.organize(document.id, folder.id)
        lock(folder.id)
        unlock(folder.id)
        val originalFiles = fs.list("/docs/documents/${document.id}/pages".toPath()).toSet()
        val result = saveScanPages(repo, document.id, "", listOf(page(2)))
        val staged = repo.stageImport(byteArrayOf(3), "合同.pdf", true)
        val pdf = repo.attachOriginalPdf(document.id, staged)
        security.background()
        repo.detachOriginalPdf(document.id, pdf)
        result.rollback(repo)
        unlock(folder.id)
        assertEquals(document.pages, repo.load(document.id)?.pages)
        assertEquals(originalFiles, fs.list("/docs/documents/${document.id}/pages".toPath()).toSet())
        assertTrue(repo.load(document.id)!!.originalPdfs.isEmpty())
        assertFalse(fs.exists("/docs/documents/${document.id}/sources/${pdf.fileName}".toPath()))
    }

    @Test
    fun unpublishedNewDocumentRollbackWorksWhileAppIsLocked() {
        val result = saveScanPages(repo, null, "未发布", listOf(page(1)))
        security.setAppLock(true)
        security.background()
        result.rollback(repo)
        assertFalse(fs.exists("/docs/documents/${result.document.id}".toPath()))
    }

    @Test
    fun duplicateAndMergeWithinProtectedFolderKeepProtection() {
        val folder = repo.createFolder("私人")
        val a = repo.create("A", listOf(page(1)))
        val b = repo.create("B", listOf(page(2)))
        repo.organize(a.id, folder.id)
        repo.organize(b.id, folder.id)
        lock(folder.id)
        unlock(folder.id)
        val copy = repo.duplicate(a.id)
        val merged = repo.merge(listOf(a.id, b.id), "合并")
        assertEquals(folder.id, copy.folderId)
        assertEquals(folder.id, merged.folderId)
        security.background()
        assertNull(repo.load(copy.id))
        assertNull(repo.load(merged.id))
    }

    @Test
    fun legacyFoldersRemainUnlockedAndSavedPoliciesSurviveRestart() {
        fs.createDirectories("/legacy".toPath())
        fs.write("/legacy/library.json".toPath()) {
            writeUtf8("""{"folders":[{"id":"old","name":"旧文件夹"}]}""")
        }
        assertFalse(DocumentRepository("/legacy", fs).folders().single().locked)
        val folder = repo.createFolder("私人")
        lock(folder.id)
        repo.saveDeviceLock(true)
        val restarted = SecurityState()
        val persisted = DocumentRepository("/docs", fs, security = restarted)
        restarted.initialize(true, persisted.settings().deviceLock, persisted.folders().filter { it.locked }.map { it.id }.toSet())
        assertTrue(restarted.appLocked)
        assertTrue(restarted.isFolderLocked(folder.id))
    }
}
