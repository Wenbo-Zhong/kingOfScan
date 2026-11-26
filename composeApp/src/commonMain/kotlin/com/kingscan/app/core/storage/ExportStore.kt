package com.kingscan.app.core.storage

import okio.Path

/** Every shared PDF/image snapshot has its own immutable path. */
internal class ExportStore(private val files: DocumentFiles) {
    private val fileSystem = files.fileSystem

    fun clearLegacy(id: String) {
        listOf("pdf", "txt").forEach { extension ->
            val file = files.exportsPath / "$id.$extension"
            runCatching { if (fileSystem.exists(file)) fileSystem.delete(file) }
        }
    }

    fun clearSnapshots(id: String) {
        val directory = files.exportsPath / id
        runCatching { if (fileSystem.exists(directory)) fileSystem.deleteRecursively(directory) }
    }

    fun snapshot(id: String, extension: String, write: (Path) -> Unit, beforeCommit: () -> Unit): String {
        val directory = files.exportsPath / id
        fileSystem.createDirectories(directory)
        val path = directory / "${files.newId()}.$extension"
        val temporary = directory / "${files.newId()}.tmp"
        try {
            write(temporary)
            beforeCommit()
            fileSystem.atomicMove(temporary, path)
        } finally {
            if (fileSystem.exists(temporary)) runCatching { fileSystem.delete(temporary) }
        }
        return path.toString()
    }
}
