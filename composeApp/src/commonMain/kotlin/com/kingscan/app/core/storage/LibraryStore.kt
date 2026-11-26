package com.kingscan.app.core.storage

import com.kingscan.app.core.document.AppSettings
import com.kingscan.app.core.document.DocumentFolder
import com.kingscan.app.core.document.SavedSignature
import kotlinx.serialization.Serializable
import okio.Path.Companion.toPath

@Serializable
internal data class LibraryMeta(
    val folders: List<DocumentFolder> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val signatures: List<SavedSignature> = emptyList(),
)

internal class LibraryStore(private val files: DocumentFiles) {
    private val path = "${files.root}/library.json".toPath()
    fun read(): LibraryMeta = if (files.fileSystem.exists(path))
        files.json.decodeFromString(LibraryMeta.serializer(), files.fileSystem.read(path) { readUtf8() })
    else LibraryMeta()

    fun write(library: LibraryMeta, beforeCommit: () -> Unit) {
        files.atomicWrite(path, files.json.encodeToString(LibraryMeta.serializer(), library).encodeToByteArray(), beforeCommit)
    }
}
