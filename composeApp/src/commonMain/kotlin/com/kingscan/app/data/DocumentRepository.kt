package com.kingscan.app.data

import com.kingscan.app.model.EncodedPage
import com.kingscan.app.model.PageMeta
import com.kingscan.app.model.ScanDocument
import com.kingscan.app.pdf.PdfImage
import com.kingscan.app.pdf.PdfWriter
import kotlin.random.Random
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class DocumentRepository(
    private val root: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val documentsPath: Path = "$root/documents".toPath()
    private val exportsPath: Path = "$root/exports".toPath()

    fun list(): List<ScanDocument> {
        if (!fileSystem.exists(documentsPath)) return emptyList()
        return fileSystem.list(documentsPath)
            .mapNotNull { directory ->
                val meta = directory / META
                if (!fileSystem.exists(meta)) null else runCatching { readDocument(meta) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    fun load(id: String): ScanDocument? {
        val meta = docDir(id) / META
        if (!fileSystem.exists(meta)) return null
        return readDocument(meta)
    }

    fun create(title: String, pages: List<EncodedPage>): ScanDocument {
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
        val directory = docDir(id)
        if (fileSystem.exists(directory)) fileSystem.deleteRecursively(directory)
        val pdf = exportsPath / "$id.pdf"
        if (fileSystem.exists(pdf)) fileSystem.delete(pdf)
    }

    fun deletePage(id: String, pageId: String): ScanDocument? {
        val document = load(id) ?: return null
        val page = document.pages.firstOrNull { it.id == pageId } ?: return document
        val file = pagesDir(id) / page.fileName
        if (fileSystem.exists(file)) fileSystem.delete(file)
        val remaining = document.pages.filterNot { it.id == pageId }
        if (remaining.isEmpty()) {
            delete(id)
            return null
        }
        val updated = document.copy(pages = remaining, updatedAt = clock())
        writeMeta(updated)
        return updated
    }

    fun movePage(id: String, pageId: String, delta: Int): ScanDocument? {
        val document = load(id) ?: return null
        val index = document.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return document
        val target = index + delta
        if (target !in document.pages.indices) return document
        val pages = document.pages.toMutableList()
        val item = pages.removeAt(index)
        pages.add(target, item)
        val updated = document.copy(pages = pages, updatedAt = clock())
        writeMeta(updated)
        return updated
    }

    fun replacePage(id: String, pageId: String, page: EncodedPage): ScanDocument? {
        val document = load(id) ?: return null
        val index = document.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return document
        val previous = document.pages[index]
        val file = pagesDir(id) / previous.fileName
        fileSystem.write(file) { write(page.jpeg) }
        val pages = document.pages.toMutableList()
        pages[index] = previous.copy(width = page.width, height = page.height, filter = page.filter)
        val updated = document.copy(pages = pages, updatedAt = clock())
        writeMeta(updated)
        return updated
    }

    fun pagePath(documentId: String, page: PageMeta): String = (pagesDir(documentId) / page.fileName).toString()

    fun readBytes(path: String): ByteArray = fileSystem.read(path.toPath()) { readByteArray() }

    fun exportPdf(id: String): String {
        val document = load(id) ?: error("文档不存在")
        require(document.pages.isNotEmpty())
        val images = document.pages.map { page ->
            PdfImage(
                jpeg = readBytes(pagePath(id, page)),
                pixelWidth = page.width,
                pixelHeight = page.height,
            )
        }
        val pdf = PdfWriter.write(images)
        fileSystem.createDirectories(exportsPath)
        val path = exportsPath / "${document.id}.pdf"
        fileSystem.write(path) { write(pdf) }
        return path.toString()
    }

    private fun writePages(document: ScanDocument, pages: List<EncodedPage>, replace: Boolean): ScanDocument {
        require(pages.isNotEmpty()) { "至少需要一页" }
        val directory = pagesDir(document.id)
        fileSystem.createDirectories(directory)
        val stored = mutableListOf<PageMeta>()
        try {
            pages.forEach { page ->
                require(page.width > 0 && page.height > 0 && page.jpeg.isNotEmpty()) { "页面图片无效" }
                val pageId = newId()
                val fileName = "$pageId.jpg"
                fileSystem.write(directory / fileName) { write(page.jpeg) }
                stored += PageMeta(
                    id = pageId,
                    fileName = fileName,
                    width = page.width,
                    height = page.height,
                    filter = page.filter,
                )
            }
            val updated = document.copy(
                pages = if (replace) stored else document.pages + stored,
                updatedAt = clock(),
            )
            writeMeta(updated)
            return updated
        } catch (failure: Throwable) {
            stored.forEach { page -> runCatching { fileSystem.delete(directory / page.fileName) } }
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
        val directory = docDir(document.id)
        fileSystem.createDirectories(directory)
        fileSystem.write(directory / META) { writeUtf8(json.encodeToString(ScanDocument.serializer(), document)) }
    }

    private fun readDocument(path: Path): ScanDocument =
        json.decodeFromString(ScanDocument.serializer(), fileSystem.read(path) { readUtf8() })

    private fun docDir(id: String): Path = documentsPath / id

    private fun pagesDir(id: String): Path = docDir(id) / "pages"

    private fun newId(): String = buildString(12) {
        repeat(12) { append(ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)]) }
    }

    companion object {
        private const val META = "meta.json"
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
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
