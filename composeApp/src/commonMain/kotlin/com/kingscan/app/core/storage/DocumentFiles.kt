package com.kingscan.app.core.storage

import com.kingscan.app.core.document.EncodedPage
import com.kingscan.app.core.document.PageMeta
import com.kingscan.app.core.document.ScanDocument
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random

/** Internal file operations. Authorization and transaction ownership stay in the repository. */
internal class DocumentFiles(val root: String, val fileSystem: FileSystem) {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    val documentsPath = "$root/documents".toPath()
    val exportsPath = "$root/exports".toPath()

    fun docDir(id: String): Path {
        require(id.isNotEmpty() && id.all { it in ID_ALPHABET }) { "文档 ID 无效" }
        return documentsPath / id
    }

    fun pagesDir(id: String): Path = docDir(id) / "pages"
    fun newId(): String = buildString(12) {
        repeat(12) { append(ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)]) }
    }

    fun readDocument(path: Path): ScanDocument =
        json.decodeFromString(ScanDocument.serializer(), fileSystem.read(path) { readUtf8() })

    fun writeDocument(document: ScanDocument, beforeCommit: () -> Unit = {}) {
        atomicWrite(docDir(document.id) / META,
            json.encodeToString(ScanDocument.serializer(), document).encodeToByteArray(), beforeCommit)
    }

    fun storePage(id: String, page: EncodedPage, pageId: String, files: MutableList<Path>): PageMeta {
        require(page.width > 0 && page.height > 0 && page.jpeg.isNotEmpty()) { "页面图片无效" }
        val directory = pagesDir(id)
        fileSystem.createDirectories(directory)
        val fileName = "${pageId}_${newId()}.jpg"
        val originalName = page.originalBytes?.let {
            require(it.isNotEmpty()) { "原始图片为空" }
            "${pageId}_${newId()}.source"
        }
        val baseName = page.annotationBaseBytes?.let { "${pageId}_${newId()}.base.jpg" }
        fun write(name: String, bytes: ByteArray) {
            val path = directory / name
            files += path
            fileSystem.write(path) { write(bytes) }
        }
        write(fileName, page.jpeg)
        if (originalName != null) write(originalName, page.originalBytes!!)
        if (baseName != null) write(baseName, page.annotationBaseBytes!!)
        return PageMeta(pageId, fileName, page.width, page.height, page.filter, originalName, page.edit, page.ocr, baseName)
    }

    fun removePageFiles(id: String, page: PageMeta, keepOriginal: String? = null, keepBase: String? = null) {
        // Cleanup must not roll back successfully committed metadata.
        listOfNotNull(page.fileName, page.originalFileName?.takeIf { it != keepOriginal },
            page.annotationBaseFileName?.takeIf { it != keepBase }).forEach {
            runCatching { fileSystem.delete(pagesDir(id) / it) }
        }
    }

    fun atomicWrite(path: Path, bytes: ByteArray, beforeCommit: () -> Unit = {}) {
        fileSystem.createDirectories(path.parent!!)
        val temporary = path.parent!! / "${path.name}.${newId()}.tmp"
        try {
            fileSystem.write(temporary) { write(bytes) }
            beforeCommit()
            fileSystem.atomicMove(temporary, path)
        } finally {
            if (fileSystem.exists(temporary)) runCatching { fileSystem.delete(temporary) }
        }
    }

    companion object {
        const val META = "meta.json"
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
