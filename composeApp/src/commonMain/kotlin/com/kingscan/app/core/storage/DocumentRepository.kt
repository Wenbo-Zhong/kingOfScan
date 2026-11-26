package com.kingscan.app.core.storage

import com.kingscan.app.core.document.EncodedPage
import com.kingscan.app.core.document.AppSettings
import com.kingscan.app.core.document.DocumentFolder
import com.kingscan.app.core.document.OcrResult
import com.kingscan.app.core.document.ImportFile
import com.kingscan.app.core.document.PageMeta
import com.kingscan.app.core.document.ScanDocument
import com.kingscan.app.core.pdf.PdfImage
import com.kingscan.app.core.pdf.PdfWriter
import com.kingscan.app.core.pdf.PdfExportPage
import com.kingscan.app.core.pdf.writeNativePdf
import com.kingscan.app.core.document.ExportOptions
import com.kingscan.app.core.document.SavedSignature
import com.kingscan.app.core.document.PageMark
import com.kingscan.app.core.document.OriginalPdf
import com.kingscan.app.core.document.CompositionOptions
import com.kingscan.app.core.security.SecurityState
import com.kingscan.app.core.security.DocumentLockedException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.kingscan.app.core.storage.DocumentFiles.Companion.META
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class DocumentRepository(
    private val root: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val security: SecurityState? = null,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val files = DocumentFiles(root, fileSystem)
    private val library = LibraryStore(files)
    private val exports = ExportStore(files)
    private val documentsPath get() = files.documentsPath
    private val exportsPath get() = files.exportsPath

    fun list(includeTrash: Boolean = false): List<ScanDocument> {
        if (security?.appLocked == true) return emptyList()
        if (!fileSystem.exists(documentsPath)) return emptyList()
        return fileSystem.list(documentsPath)
            .mapNotNull { directory ->
                val meta = directory / META
                if (!fileSystem.exists(meta)) null else runCatching { files.readDocument(meta) }.getOrNull()
            }
            .filter { (includeTrash || it.deletedAt == null) && canAccessFolder(it.folderId) }
            .sortedByDescending { it.updatedAt }
    }

    fun load(id: String, includeTrash: Boolean = false): ScanDocument? {
        return rawLoad(id)?.takeIf { (includeTrash || it.deletedAt == null) && canAccessFolder(it.folderId) }
    }

    private fun rawLoad(id: String): ScanDocument? {
        val meta = docDir(id) / META
        if (!fileSystem.exists(meta)) return null
        return files.readDocument(meta)
    }

    fun create(title: String, pages: List<EncodedPage>): ScanDocument {
        requireFolderAccess(null)
        require(pages.isNotEmpty())
        val now = clock()
        val document = ScanDocument(
            id = newId(),
            title = uniqueTitle(title.trim().ifBlank { "未命名扫描件" }),
            createdAt = now,
            updatedAt = now,
            pages = emptyList(),
        )
        fileSystem.createDirectories(pagesDir(document.id))
        return try {
            writePages(document, pages, replace = true)
        } catch (failure: Throwable) {
            runCatching { fileSystem.deleteRecursively(docDir(document.id)) }
            throw failure
        }
    }

    fun addPages(id: String, pages: List<EncodedPage>): ScanDocument {
        val document = load(id) ?: error("文档不存在")
        return writePages(document, pages, replace = false)
    }

    fun rename(id: String, title: String): ScanDocument? {
        val document = load(id) ?: return null
        val updated = document.copy(
            title = uniqueTitle(title.trim().ifBlank { document.title }, ignoring = document.id),
            updatedAt = clock(),
        )
        writeMeta(updated)
        return updated
    }

    fun delete(id: String) {
        val document = load(id) ?: return
        writeMeta(document.copy(deletedAt = clock(), updatedAt = clock()))
        clearExport(id)
        clearPdfSnapshots(id)
    }

    fun restore(id: String): ScanDocument? {
        val document = load(id, includeTrash = true) ?: return null
        val restored = document.copy(deletedAt = null, updatedAt = clock())
        writeMeta(restored)
        return restored
    }

    /** Called only by the explicit "彻底删除" action in the recycle bin. */
    fun purge(id: String) {
        val document = load(id, includeTrash = true) ?: return
        require(document.deletedAt != null) { "请先将文档移入回收站" }
        val directory = docDir(id)
        if (fileSystem.exists(directory)) fileSystem.deleteRecursively(directory)
        clearExport(id)
        clearPdfSnapshots(id)
    }

    private fun clearPdfSnapshots(id: String) = exports.clearSnapshots(id)

    private fun clearExport(id: String) = exports.clearLegacy(id)

    fun deletePage(id: String, pageId: String): ScanDocument? {
        val document = load(id) ?: return null
        val page = document.pages.firstOrNull { it.id == pageId } ?: return document
        val remaining = document.pages.filterNot { it.id == pageId }
        if (remaining.isEmpty()) {
            delete(id)
            return null
        }
        val updated = document.copy(pages = remaining, updatedAt = clock())
        writeMeta(updated)
        removePageFiles(id, page)
        clearExport(id)
        return updated
    }

    fun deletePages(id: String, pageIds: List<String>): ScanDocument? {
        val document = load(id) ?: return null
        require(pageIds.isNotEmpty() && pageIds.distinct().size == pageIds.size &&
            pageIds.all { selected -> document.pages.any { it.id == selected } }) { "页面选择无效" }
        val remaining = document.pages.filterNot { it.id in pageIds }
        if (remaining.isEmpty()) { delete(id); return null }
        val updated = document.copy(pages = remaining, updatedAt = clock())
        writeMeta(updated)
        document.pages.filter { it.id in pageIds }.forEach { removePageFiles(id, it) }
        clearExport(id)
        return updated
    }

    fun movePage(id: String, pageId: String, delta: Int): ScanDocument? {
        val document = load(id) ?: return null
        val index = document.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return document
        val target = index + delta
        if (target !in document.pages.indices) return document
        val order = document.pages.map { it.id }.toMutableList()
        val item = order.removeAt(index)
        order.add(target, item)
        return reorderPages(id, order)
    }

    fun reorderPages(id: String, order: List<String>): ScanDocument? {
        val document = load(id) ?: return null
        val pages = document.pages.associateBy { it.id }
        require(order.size == pages.size && order.toSet() == pages.keys) { "页面排序必须包含每一页且不能重复" }
        val updated = document.copy(pages = order.map { pages.getValue(it) }, updatedAt = clock())
        writeMeta(updated)
        clearExport(id)
        return updated
    }

    fun replacePage(id: String, pageId: String, page: EncodedPage, keepAnnotationBase: Boolean = false): ScanDocument? {
        val document = load(id) ?: return null
        val index = document.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return document
        val previous = document.pages[index]
        val createdFiles = mutableListOf<Path>()
        try {
            val replacement = files.storePage(id, page, previous.id, createdFiles).let {
                it.copy(originalFileName = it.originalFileName ?: previous.originalFileName,
                    annotationBaseFileName = it.annotationBaseFileName ?: previous.annotationBaseFileName.takeIf { keepAnnotationBase })
            }
            val pages = document.pages.toMutableList()
            pages[index] = replacement
            val updated = document.copy(pages = pages, updatedAt = clock())
            writeMeta(updated)
            // Commit metadata before removing the previous immutable image.
            removePageFiles(id, previous, keepOriginal = replacement.originalFileName, keepBase = replacement.annotationBaseFileName)
            clearExport(id)
            return updated
        } catch (failure: Throwable) {
            createdFiles.forEach { runCatching { fileSystem.delete(it) } }
            throw failure
        }
    }

    fun pagePath(documentId: String, page: PageMeta): String {
        requireDocumentAccess(documentId)
        return (pagesDir(documentId) / page.fileName).toString()
    }

    fun originalPath(documentId: String, page: PageMeta): String? {
        requireDocumentAccess(documentId)
        return page.originalFileName?.let { (pagesDir(documentId) / it).toString() }
    }

    fun annotationBasePath(documentId: String, page: PageMeta): String {
        requireDocumentAccess(documentId)
        return (pagesDir(documentId) / (page.annotationBaseFileName ?: page.fileName)).toString()
    }

    fun originalPdfPath(documentId: String, pdf: OriginalPdf): String {
        requireDocumentAccess(documentId)
        return (docDir(documentId) / "sources" / pdf.fileName).toString()
    }

    /** Preserve the imported vector PDF separately from the editable raster pages. */
    fun attachOriginalPdf(id: String, file: ImportFile): OriginalPdf {
        require(file.isPdf)
        val document = load(id) ?: error("文档不存在")
        val pdf = OriginalPdf(file.name, "${newId()}.pdf")
        val path = originalPdfPath(id, pdf).toPath()
        fileSystem.createDirectories(path.parent!!)
        try {
            fileSystem.copy(file.path.toPath(), path)
            writeMeta(document.copy(originalPdfs = document.originalPdfs + pdf))
            return pdf
        } catch (failure: Throwable) {
            runCatching { fileSystem.delete(path) }
            throw failure
        }
    }

    /** Remove only the source attached by a failed or cancelled import transaction. */
    internal fun detachOriginalPdf(id: String, pdf: OriginalPdf) {
        val document = rawLoad(id) ?: return
        if (pdf !in document.originalPdfs) return
        writeMetaUnchecked(document.copy(originalPdfs = document.originalPdfs - pdf))
        fileSystem.delete(docDir(id) / "sources" / pdf.fileName)
    }

    /** Transaction-owned cleanup must remain possible after background revokes access. */
    internal fun rollbackOwnedPages(id: String, created: Boolean, addedIds: List<String>) {
        val document = rawLoad(id) ?: return
        if (created) {
            // An unpublished newly-created document belongs entirely to the transaction.
            fileSystem.deleteRecursively(docDir(id))
            clearExport(id)
            clearPdfSnapshots(id)
        } else {
            val owned = document.pages.filter { it.id in addedIds }
            if (owned.isEmpty()) return
            val remaining = document.pages.filterNot { it.id in addedIds }
            writeMetaUnchecked(document.copy(pages = remaining, updatedAt = clock()))
            owned.forEach { removePageFiles(id, it) }
            clearExport(id)
        }
    }

    fun canAccessFolder(id: String?): Boolean = security?.canAccessFolder(id) != false
    private fun requireFolderAccess(id: String?) {
        if (!canAccessFolder(id)) throw DocumentLockedException()
    }
    fun requireDocumentAccess(id: String) { requireFolderAccess(rawLoad(id)?.folderId) }

    /** Only the association is inspected for the root overlay; no protected metadata is exposed. */
    fun blockedFolderForDocument(id: String?): String? =
        id?.let { rawLoad(it)?.folderId }?.takeIf { !canAccessFolder(it) }

    fun requirePathAccess(path: String) {
        requireFolderAccess(null)
        val normalized = path.toPath(normalize = true)
        val base = root.toPath(normalize = true)
        if (normalized.segments.take(base.segments.size) != base.segments) return
        val relative = normalized.segments.drop(base.segments.size)
        when (relative.firstOrNull()) {
            "documents" -> relative.getOrNull(1)?.let(::requireDocumentAccess)
            "exports" -> relative.getOrNull(1)?.let { name ->
                if (name != "qrcode.jpg") requireDocumentAccess(name.substringBefore('.'))
            }
        }
    }

    fun readBytes(path: String): ByteArray {
        requirePathAccess(path)
        return fileSystem.read(path.toPath()) { readByteArray() }.also { requirePathAccess(path) }
    }

    /** System share/print receives an immutable copy instead of a live protected source. */
    fun prepareOutput(path: String): String {
        requirePathAccess(path)
        val source = path.toPath(normalize = true)
        if (source.segments.take(exportsPath.segments.size) == exportsPath.segments) return path
        val relative = source.segments.drop(documentsPath.segments.size)
        require(source.segments.take(documentsPath.segments.size) == documentsPath.segments && relative.size >= 2) {
            "输出文件路径无效"
        }
        return exports.snapshot(relative.first(), source.name.substringAfterLast('.', "bin"),
            write = { fileSystem.copy(source, it) }, beforeCommit = { requirePathAccess(path) })
    }

    fun exportCodeImage(bytes: ByteArray): String {
        requireFolderAccess(null)
        require(bytes.isNotEmpty())
        val path = exportsPath / "qrcode.jpg"
        atomicWrite(path, bytes)
        return path.toString()
    }

    fun stageImport(bytes: ByteArray, name: String, isPdf: Boolean = false): ImportFile {
        require(bytes.isNotEmpty()) { "文件为空" }
        val path = "$root/imports/${newId()}.${if (isPdf) "pdf" else "image"}".toPath()
        atomicWrite(path, bytes)
        return ImportFile(path.toString(), name, isPdf)
    }

    fun finishImport(file: ImportFile) {
        val path = file.path.toPath()
        require(path.parent == "$root/imports".toPath()) { "导入文件路径无效" }
        runCatching { fileSystem.delete(path) }
    }

    fun setOcr(id: String, pageId: String, result: OcrResult): ScanDocument? {
        val document = load(id) ?: return null
        require(document.pages.any { it.id == pageId }) { "页面不存在" }
        val updated = document.copy(
            pages = document.pages.map { if (it.id == pageId) it.copy(ocr = result) else it },
            updatedAt = clock(),
        )
        writeMeta(updated)
        clearExport(id)
        return updated
    }

    fun organize(
        id: String,
        folderId: String? = load(id)?.folderId,
        tags: List<String> = load(id)?.tags.orEmpty(),
        starred: Boolean = load(id)?.starred ?: false,
        notes: String = load(id)?.notes.orEmpty(),
    ): ScanDocument? {
        val document = load(id) ?: return null
        require(folderId == null || folders().any { it.id == folderId }) { "文件夹不存在" }
        val updated = document.copy(
            folderId = folderId,
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            starred = starred,
            notes = notes.trim(),
            updatedAt = clock(),
        )
        writeMeta(updated)
        return updated
    }

    fun search(query: String, includeTrash: Boolean = false): List<ScanDocument> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return list(includeTrash).filter { document ->
            val text = listOf(document.title, document.notes, document.tags.joinToString(" "),
                document.pages.mapNotNull { it.ocr?.text }.joinToString(" ")).joinToString("\n")
            terms.all { text.contains(it, ignoreCase = true) }
        }
    }

    fun duplicate(id: String): ScanDocument =
        extractPages(id, load(id)?.pages?.map { it.id }.orEmpty(), "${load(id)?.title} 副本")

    fun extractPages(id: String, pageIds: List<String>, title: String): ScanDocument {
        val source = load(id) ?: error("文档不存在")
        require(pageIds.isNotEmpty() && pageIds.distinct().size == pageIds.size) { "请选择不同的页面" }
        val pages = source.pages.associateBy { it.id }
        require(pageIds.all { it in pages }) { "页面不存在" }
        val created = copyStoredPages(title, pageIds.map { source to pages.getValue(it) })
        return organize(created.id, source.folderId, source.tags, notes = source.notes)!!
    }

    /** The selected document order and each document's current page order are preserved. */
    fun merge(ids: List<String>, title: String): ScanDocument {
        require(ids.size >= 2 && ids.distinct().size == ids.size) { "至少选择两份不同的文档" }
        val sources = ids.map { load(it) ?: error("文档不存在") }
        return copyStoredPages(title, sources.flatMap { document -> document.pages.map { document to it } })
    }

    /** Deep copy streams files instead of loading every source image into a large list. */
    private fun copyStoredPages(title: String, sources: List<Pair<ScanDocument, PageMeta>>): ScanDocument {
        require(sources.isNotEmpty()) { "至少需要一页" }
        val now = clock()
        val destination = ScanDocument(newId(), uniqueTitle(title.trim().ifBlank { "未命名扫描件" }), now, now, emptyList(),
            folderId = sources.map { it.first.folderId }.distinct().singleOrNull())
        fileSystem.createDirectories(pagesDir(destination.id))
        return try {
            fun copyPageFile(source: ScanDocument, name: String, extension: String): String {
                requireDocumentAccess(source.id)
                val newName = "${newId()}.$extension"
                fileSystem.copy(pagesDir(source.id) / name, pagesDir(destination.id) / newName)
                return newName
            }
            val copied = sources.map { (source, page) ->
                page.copy(id = newId(), fileName = copyPageFile(source, page.fileName, "jpg"),
                    originalFileName = page.originalFileName?.let { copyPageFile(source, it, "source") },
                    annotationBaseFileName = page.annotationBaseFileName?.let { copyPageFile(source, it, "base.jpg") })
            }
            val componentIds = mutableMapOf<String, String>()
            val componentPages = sources.map { it.first }.distinctBy { it.id }.flatMap { source ->
                source.sourcePages.map { page ->
                    val componentId = newId()
                    componentIds["${source.id}/${page.id}"] = componentId
                    page.copy(id = componentId, fileName = copyPageFile(source, page.fileName, "jpg"),
                        originalFileName = page.originalFileName?.let { copyPageFile(source, it, "source") },
                        annotationBaseFileName = page.annotationBaseFileName?.let { copyPageFile(source, it, "base.jpg") })
                }
            }
            val pdfs = sources.map { it.first }.distinctBy { it.id }.flatMap { source ->
                source.originalPdfs.map { pdf ->
                    val newPdf = pdf.copy(fileName = "${newId()}.pdf")
                    val path = originalPdfPath(destination.id, newPdf).toPath()
                    fileSystem.createDirectories(path.parent!!)
                    fileSystem.copy(originalPdfPath(source.id, pdf).toPath(), path)
                    newPdf
                }
            }
            val singleSource = sources.map { it.first }.distinctBy { it.id }.singleOrNull()
                ?.takeIf { it.composition != null && sources.all { (_, page) -> page in it.pages } }
            sources.map { it.first.id }.distinct().forEach(::requireDocumentAccess)
            destination.copy(pages = copied, sourcePages = componentPages, originalPdfs = pdfs,
                composition = singleSource?.composition,
                compositionSourceIds = singleSource?.compositionSourceIds?.mapNotNull { componentIds["${singleSource.id}/$it"] }.orEmpty()
            ).also(::writeMeta)
        } catch (failure: Throwable) {
            runCatching { fileSystem.deleteRecursively(docDir(destination.id)) }
            throw failure
        }
    }

    /** Composition retains deep copies of every input page and its camera original. */
    fun composePages(id: String, pageIds: List<String>, page: EncodedPage, options: CompositionOptions): ScanDocument {
        val source = load(id) ?: error("文档不存在")
        val available = (source.pages + source.sourcePages).associateBy { it.id }
        require(pageIds.isNotEmpty() && pageIds.distinct().size == pageIds.size && pageIds.all { it in available }) { "页面选择无效" }
        val destination = copyStoredPages("${source.title} ${when (options.kind) {
            com.kingscan.app.core.document.CompositionKind.IdCard -> "证件复印件"
            com.kingscan.app.core.document.CompositionKind.Overlap -> "拼接"
            else -> "拼版"
        }}", pageIds.map { source to available.getValue(it) })
        return try {
            val inputs = destination.pages
            val withOutput = addPages(destination.id, listOf(page))
            withOutput.copy(pages = listOf(withOutput.pages.last()), sourcePages = destination.sourcePages + inputs,
                composition = options, compositionSourceIds = inputs.map { it.id }).also(::writeMeta)
        } catch (failure: Throwable) {
            runCatching { fileSystem.deleteRecursively(docDir(destination.id)) }
            throw failure
        }
    }

    fun extractCompositionSources(id: String): ScanDocument {
        val source = load(id) ?: error("文档不存在")
        val selected = source.sourcePages.filter { it.id in source.compositionSourceIds }
        require(selected.isNotEmpty()) { "文档没有拼版原页" }
        return copyStoredPages("${source.title} 原页", selected.map { source to it })
    }

    fun folders(): List<DocumentFolder> = readLibrary().folders

    fun createFolder(name: String): DocumentFolder {
        requireFolderAccess(null)
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入文件夹名称" }
        val library = readLibrary()
        require(library.folders.none { it.name == trimmed }) { "文件夹已存在" }
        val folder = DocumentFolder(newId(), trimmed)
        writeLibrary(library.copy(folders = library.folders + folder))
        return folder
    }

    fun renameFolder(id: String, name: String) {
        requireFolderAccess(id)
        val trimmed = name.trim()
        val library = readLibrary()
        require(trimmed.isNotEmpty() && library.folders.none { it.id != id && it.name == trimmed }) { "名称为空或重复" }
        require(library.folders.any { it.id == id }) { "文件夹不存在" }
        writeLibrary(library.copy(folders = library.folders.map { if (it.id == id) it.copy(name = trimmed) else it }))
    }

    fun deleteFolder(id: String) {
        requireFolderAccess(id)
        require(security?.isFolderLocked(id) != true) { "请先关闭文件夹锁，再移除文件夹" }
        // Documents remain in the library; only their folder association is removed.
        list(includeTrash = true).filter { it.folderId == id }.forEach { writeMeta(it.copy(folderId = null)) }
        val library = readLibrary()
        writeLibrary(library.copy(folders = library.folders.filterNot { it.id == id }))
        security?.removeFolder(id)
    }

    internal fun saveFolderLock(id: String, locked: Boolean) {
        requireFolderAccess(null)
        check(security?.supported != false) { "此设备不支持系统生物识别" }
        val library = readLibrary()
        require(library.folders.any { it.id == id }) { "文件夹不存在" }
        writeLibrary(library.copy(folders = library.folders.map { if (it.id == id) it.copy(locked = locked) else it }))
    }

    internal fun saveDeviceLock(enabled: Boolean) {
        requireFolderAccess(null)
        check(security?.supported != false) { "此设备不支持系统生物识别" }
        val library = readLibrary()
        writeLibrary(library.copy(settings = library.settings.copy(deviceLock = enabled)))
    }

    fun settings(): AppSettings = readLibrary().settings

    fun signatures(): List<SavedSignature> {
        requireFolderAccess(null)
        return readLibrary().signatures
    }

    fun saveSignature(name: String, marks: List<PageMark>, pageAspectRatio: Float) {
        val points = marks.flatMap { it.points }
        require(points.size >= 2) { "请先书写签名" }
        val xs = points.map { it.x }; val ys = points.map { it.y }
        val minX = xs.min(); val minY = ys.min()
        val width = (xs.max() - minX).coerceAtLeast(0.01f)
        val height = (ys.max() - minY).coerceAtLeast(0.01f)
        val paths = marks.map { mark -> mark.points.map {
            com.kingscan.app.core.document.PointF((it.x - minX) / width, (it.y - minY) / height)
        } }
        val signature = SavedSignature(newId(), name.trim().ifBlank { "我的签名" },
            paths.first(), marks.first().strokeWidth, paths, width * pageAspectRatio / height)
        val library = readLibrary()
        writeLibrary(library.copy(signatures = library.signatures + signature))
    }

    fun deleteSignature(id: String) {
        val library = readLibrary()
        writeLibrary(library.copy(signatures = library.signatures.filterNot { it.id == id }))
    }

    fun saveSettings(settings: AppSettings) {
        val library = readLibrary()
        require(security == null || settings.deviceLock == library.settings.deviceLock) { "修改应用锁需要系统生物识别认证" }
        writeLibrary(library.copy(settings = settings))
    }

    fun exportText(id: String): String {
        val document = load(id) ?: error("文档不存在")
        require(document.pages.any { !it.ocr?.text.isNullOrBlank() }) { "请先识别文字" }
        val path = exportsPath / "$id.txt"
        atomicWrite(path, document.pages.mapIndexed { index, page ->
            "—— 第 ${index + 1} 页 ——\n${page.ocr?.text.orEmpty()}"
        }.joinToString("\n\n").encodeToByteArray())
        return path.toString()
    }

    fun exportPdf(id: String): String {
        val document = load(id) ?: error("文档不存在")
        require(document.pages.isNotEmpty())
        val images = document.pages.map { page ->
            PdfImage(
                jpeg = readBytes(pagePath(id, page)),
                pixelWidth = page.width,
                pixelHeight = page.height,
                physicalWidthPt = 595.28f.takeIf { document.composition?.kind == com.kingscan.app.core.document.CompositionKind.IdCard },
                physicalHeightPt = 841.89f.takeIf { document.composition?.kind == com.kingscan.app.core.document.CompositionKind.IdCard },
            )
        }
        val pdf = PdfWriter.write(images)
        requireDocumentAccess(id)
        return exports.snapshot(id, "pdf", { path -> fileSystem.write(path) { write(pdf) } },
            beforeCommit = { requireDocumentAccess(id) })
    }

    fun exportPdf(id: String, options: ExportOptions): String {
        val document = load(id) ?: error("文档不存在")
        val layoutOptions = if (document.composition?.kind == com.kingscan.app.core.document.CompositionKind.IdCard)
            options.copy(paper = com.kingscan.app.core.document.PdfPaper.A4, landscape = false, marginPoints = 0f)
        else options
        return exports.snapshot(id, "pdf", write = { temporary ->
            writeNativePdf(document.pages.map {
                PdfExportPage(pagePath(id, it), it.width, it.height, it.ocr)
            }, layoutOptions, temporary.toString(), document.title)
        }, beforeCommit = { requireDocumentAccess(id) })
    }

    private fun writePages(document: ScanDocument, pages: List<EncodedPage>, replace: Boolean): ScanDocument {
        require(pages.isNotEmpty()) { "至少需要一页" }
        val directory = pagesDir(document.id)
        fileSystem.createDirectories(directory)
        val createdFiles = mutableListOf<Path>()
        try {
            val stored = pages.map { files.storePage(document.id, it, newId(), createdFiles) }
            val updated = document.copy(
                pages = if (replace) stored else document.pages + stored,
                updatedAt = clock(),
            )
            writeMeta(updated)
            clearExport(document.id)
            return updated
        } catch (failure: Throwable) {
            createdFiles.forEach { runCatching { fileSystem.delete(it) } }
            if (replace) runCatching { fileSystem.deleteRecursively(docDir(document.id)) }
            throw failure
        }
    }

    private fun uniqueTitle(raw: String, ignoring: String? = null): String {
        val taken = list().filter { it.id != ignoring }.map { it.title }.toSet()
        if (raw !in taken) return raw
        var index = 2
        while ("$raw $index" in taken) index++
        return "$raw $index"
    }

    private fun writeMeta(document: ScanDocument) {
        requireDocumentAccess(document.id)
        requireFolderAccess(document.folderId)
        files.writeDocument(document) {
            requireDocumentAccess(document.id)
            requireFolderAccess(document.folderId)
        }
    }

    private fun writeMetaUnchecked(document: ScanDocument) {
        files.writeDocument(document)
    }

    private fun removePageFiles(id: String, page: PageMeta, keepOriginal: String? = null, keepBase: String? = null) =
        files.removePageFiles(id, page, keepOriginal, keepBase)

    private fun atomicWrite(path: Path, bytes: ByteArray, beforeCommit: () -> Unit = {}) =
        files.atomicWrite(path, bytes, beforeCommit)

    private fun readLibrary(): LibraryMeta = library.read()

    private fun writeLibrary(library: LibraryMeta) {
        requireFolderAccess(null)
        this.library.write(library) { requireFolderAccess(null) }
    }

    private fun docDir(id: String): Path = files.docDir(id)
    private fun pagesDir(id: String): Path = files.pagesDir(id)
    private fun newId(): String = files.newId()
}

fun defaultScanTitle(nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
    val local = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "扫描件 ${local.monthNumber}月${local.dayOfMonth}日 $hour:$minute"
}

fun formatScanTime(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun pad(value: Int) = value.toString().padStart(2, '0')
    return "${local.year}-${pad(local.monthNumber)}-${pad(local.dayOfMonth)} ${pad(local.hour)}:${pad(local.minute)}"
}
