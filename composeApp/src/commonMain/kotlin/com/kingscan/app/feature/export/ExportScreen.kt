package com.kingscan.app.feature.export

import com.kingscan.app.core.ui.ScanTopBar
import com.kingscan.app.core.platform.rememberShareLauncher
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.feature.editor.rememberEditorDocument
import com.kingscan.app.feature.settings.ExportOptionsEditor
import com.kingscan.app.navigation.Navigator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.kingscan.app.core.ui.ScanButton as Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.core.ocr.OcrLanguage
import com.kingscan.app.core.ocr.OcrSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExportScreen(documentId: String, navigator: Navigator, onMessage: (String) -> Unit) {
    val repository = AppGraph.repository
    val document = rememberEditorDocument(documentId).value ?: return
    val physicalIdCard = document.composition?.kind == com.kingscan.app.core.document.CompositionKind.IdCard
    var options by remember { mutableStateOf(repository.settings().export.copy(password = "").let {
        if (physicalIdCard) it.copy(paper = com.kingscan.app.core.document.PdfPaper.A4, landscape = false, marginPoints = 0f) else it
    }) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("正在生成 PDF") }
    val scope = rememberCoroutineScope()
    val share = rememberShareLauncher(onMessage)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScanTopBar("导出 PDF · ${document.pages.size} 页", onBack = { if (!busy) navigator.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExportOptionsEditor(options, { options = it }, fixedIdPaper = physicalIdCard)
            }
            Button(onClick = {
                val snapshot = options
                scope.launch {
                    busy = true
                    try {
                        if (snapshot.searchable) {
                            AppGraph.ocr.recognize(documentId, OcrLanguage.Chinese, selection = OcrSelection.Missing,
                                onStart = { _, index -> progress = "正在本机识别第 ${index + 1} 页" })
                        }
                        progress = "正在生成 PDF"
                        val path = withContext(Dispatchers.Default) { repository.exportPdf(documentId, snapshot) }
                        share(path, "application/pdf", document.title)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) { onMessage(failure.message ?: "生成 PDF 失败") }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("生成并分享") }
        }
        BusyOverlay(busy, progress)
    }
}
