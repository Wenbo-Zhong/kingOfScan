package com.kingscan.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.kingscan.app.ui.ScanButton as Button
import com.kingscan.app.ui.ScanTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.kingscan.app.ui.ScanTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.imaging.ScanLimits
import com.kingscan.app.model.EncodedPage
import com.kingscan.app.model.FilterType
import com.kingscan.app.model.ImportFile
import com.kingscan.app.model.PageEdit
import com.kingscan.app.model.Screen
import com.kingscan.app.pdf.PdfPasswordRequired
import com.kingscan.app.pdf.openPdfSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun importFiles(
    files: List<ImportFile>, setBusy: (Boolean) -> Unit,
    navigator: Navigator, onMessage: (String) -> Unit,
) {
    AppGraph.session.pendingImports.addAll(files)
    processNextImport(setBusy, navigator, onMessage)
}

/** Staged paths keep a multi-page import from holding every full-resolution image in memory. */
internal fun processNextImport(
    setBusy: (Boolean) -> Unit, navigator: Navigator,
    onMessage: (String) -> Unit, retry: ImportFile? = null, password: String = "",
) {
    val session = AppGraph.session
    val repository = AppGraph.repository
    // Import work belongs to the navigator, so replacing an unlock screen does not cancel the next file.
    session.processingJob = navigator.scope.launch(start = CoroutineStart.LAZY) {
        var next = retry ?: session.pendingImports.removeFirstOrNull()
        setBusy(true)
        while (next != null) {
            val file = next
            session.activeImport = file
            if (!file.isPdf) {
                navigator.finishEditor()
                val result = withContext(Dispatchers.Default) { runCatching { repository.readBytes(file.path) } }
                repository.finishImport(file)
                session.activeImport = null
                setBusy(false)
                result.fold(
                    { openEditor(it, setBusy, navigator, onMessage) },
                    {
                        onMessage("${file.name}：${it.message ?: "读取失败"}")
                        processNextImport(setBusy, navigator, onMessage)
                    },
                )
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val source = openPdfSource(file.path, if (file == retry) password else "")
                    val previousId = session.documentId
                    var createdId: String? = null
                    val addedIds = mutableListOf<String>()
                    try {
                        require(source.pageCount > 0) { "PDF 没有页面" }
                        for (index in 0 until source.pageCount) {
                            val image = source.renderPage(index, ScanLimits.MAX_EDGE)
                            val bytes = AppGraph.codec.encodeJpeg(image, ScanLimits.JPEG_QUALITY)
                            val page = EncodedPage(bytes, image.width, image.height, FilterType.Original,
                                originalBytes = bytes, edit = PageEdit(filter = FilterType.Original))
                            val updated = if (session.documentId == null) repository.create(file.name.substringBeforeLast('.'), listOf(page))
                                else repository.addPages(session.documentId!!, listOf(page))
                            if (previousId == null && createdId == null) createdId = updated.id
                            session.documentId = updated.id
                            addedIds += updated.pages.last().id
                        }
                        repository.attachOriginalPdf(session.documentId!!, file)
                    } catch (failure: Throwable) {
                        // Roll back only this file, leaving earlier successful imports intact.
                        if (createdId != null) { repository.delete(createdId); repository.purge(createdId) }
                        else if (previousId != null) addedIds.forEach { repository.deletePage(previousId, it) }
                        session.documentId = previousId
                        throw failure
                    } finally { source.close() }
                }
            }
            if (result.exceptionOrNull() is PdfPasswordRequired) {
                setBusy(false)
                if (file == retry) onMessage("密码不正确")
                else navigator.push(Screen.PdfUnlock(file))
                return@launch
            }
            repository.finishImport(file)
            session.activeImport = null
            result.exceptionOrNull()?.let { onMessage("${file.name}：${it.message ?: "导入失败"}") }
            next = session.pendingImports.removeFirstOrNull()
        }
        setBusy(false)
        navigator.finishEditor()
        session.documentId?.let(navigator::showDetail)
    }
    session.processingJob?.start()
}

@Composable
fun PdfUnlockScreen(file: ImportFile, navigator: Navigator, onMessage: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp)) {
            Text("打开加密 PDF：${file.name}")
            OutlinedTextField(password, { password = it }, label = { Text("PDF 密码") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Button(onClick = {
                processNextImport({ busy = it }, navigator, onMessage, file, password)
            }, enabled = !busy) { Text("解锁并导入") }
            TextButton(onClick = {
                AppGraph.repository.finishImport(file)
                AppGraph.session.activeImport = null
                navigator.pop()
                processNextImport({ busy = it }, navigator, onMessage)
            }, enabled = !busy) { Text("跳过此文件") }
        }
        BusyOverlay(busy, "正在导入 PDF")
    }
}
