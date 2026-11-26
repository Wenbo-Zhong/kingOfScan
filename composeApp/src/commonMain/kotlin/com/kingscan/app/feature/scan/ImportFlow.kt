package com.kingscan.app.feature.scan

import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.navigation.Navigator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.kingscan.app.core.ui.ScanButton as Button
import com.kingscan.app.core.ui.ScanTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanTextButton as TextButton
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
import com.kingscan.app.core.document.ImportFile
import com.kingscan.app.navigation.Screen
import com.kingscan.app.core.pdf.PdfPasswordRequired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    session.launchProcessing(navigator.scope) {
        try {
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
                    return@launchProcessing
                }
                val targetId = session.documentId
                val result = runCatching {
                    writeAndPublish(
                        work = { checkActive ->
                            importPdf(repository, AppGraph.codec, targetId, file, if (file == retry) password else "", checkActive)
                        },
                        rollback = { it.rollback(repository) },
                        publish = { session.documentId = it.pages.document.id },
                    )
                }
                coroutineContext.ensureActive()
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                if (result.exceptionOrNull() is PdfPasswordRequired) {
                    setBusy(false)
                    if (file == retry) onMessage("密码不正确")
                    else navigator.push(Screen.PdfUnlock(file))
                    return@launchProcessing
                }
                repository.finishImport(file)
                session.activeImport = null
                result.exceptionOrNull()?.let { onMessage("${file.name}：${it.message ?: "导入失败"}") }
                next = session.pendingImports.removeFirstOrNull()
            }
            navigator.finishEditor()
            session.documentId?.let(navigator::showDetail)
        } finally {
            if (session.processingJob == coroutineContext[Job]) setBusy(false)
        }
    }
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
