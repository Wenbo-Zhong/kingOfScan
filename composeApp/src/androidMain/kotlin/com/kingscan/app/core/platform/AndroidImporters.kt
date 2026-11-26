package com.kingscan.app.core.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.kingscan.app.core.document.ImportFile
import com.kingscan.app.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
actual fun rememberImagesImporter(
    onImages: (List<ImportFile>) -> Unit,
    onError: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onImages)
    val latestError = rememberUpdatedState(onError)
    val latestStart = rememberUpdatedState(onStart)
    val latestCancel = rememberUpdatedState(onCancel)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) {
            latestCancel.value.invoke()
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            val staged = mutableListOf<ImportFile>()
            val result = runCatching {
                uris.forEachIndexed { index, uri ->
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取所选图片")
                    staged += AppGraph.repository.stageImport(bytes, "相册 ${index + 1}")
                }
                staged.toList()
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                result.fold({ latest.value.invoke(it) }, {
                    staged.forEach { file -> AppGraph.repository.finishImport(file) }
                    latestError.value.invoke(it.message ?: "无法读取所选图片")
                })
            }
        }
    }
    return {
        latestStart.value.invoke()
        runCatching {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure { latestError.value.invoke("无法打开相册") }
    }
}

@Composable
actual fun rememberFileImporter(
    onFiles: (List<ImportFile>) -> Unit,
    onError: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val latestFiles = rememberUpdatedState(onFiles)
    val latestError = rememberUpdatedState(onError)
    val latestCancel = rememberUpdatedState(onCancel)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) latestCancel.value() else scope.launch(Dispatchers.IO) {
            val staged = mutableListOf<ImportFile>()
            val result = runCatching {
                uris.forEach { uri ->
                    val mime = context.contentResolver.getType(uri)
                    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "导入文件"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取 $name")
                    val pdf = mime == "application/pdf" || bytes.take(5).toByteArray().contentEquals("%PDF-".encodeToByteArray())
                    staged += AppGraph.repository.stageImport(bytes, name, pdf)
                }
                staged.toList()
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                result.fold({ latestFiles.value(it) }, {
                    staged.forEach { file -> AppGraph.repository.finishImport(file) }
                    latestError.value(it.message ?: "无法读取文件")
                })
            }
        }
    }
    return {
        onStart()
        runCatching { launcher.launch(arrayOf("application/pdf", "image/*")) }
            .onFailure { onError("无法打开文件选择器") }
    }
}
