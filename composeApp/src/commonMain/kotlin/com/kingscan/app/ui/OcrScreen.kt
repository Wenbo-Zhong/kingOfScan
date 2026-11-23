package com.kingscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.kingscan.app.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.ui.ScanTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.kingscan.app.ui.ScanTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.imaging.ScanLimits
import com.kingscan.app.model.OcrResult
import com.kingscan.app.ocr.OcrLanguage
import com.kingscan.app.ocr.createOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OcrScreen(documentId: String, pageId: String, navigator: Navigator, onMessage: (String) -> Unit) {
    val repository = AppGraph.repository
    val document = remember(documentId) { repository.load(documentId) } ?: return
    val page = remember(pageId) { document.pages.firstOrNull { it.id == pageId } } ?: return
    var text by remember { mutableStateOf(page.ocr?.text.orEmpty()) }
    var result by remember { mutableStateOf(page.ocr) }
    var busy by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(OcrLanguage.Chinese) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val share = rememberShareLauncher()
    fun persist() {
        // Preserve the exact bounding boxes only while the recognized text is unchanged.
        val updated = if (result?.text == text) result!! else OcrResult(text, languages = result?.languages.orEmpty())
        repository.setOcr(documentId, pageId, updated)
        result = updated
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScanTopBar("文字识别", onBack = { if (!busy) navigator.pop() }) {
                TextButton(onClick = { persist(); onMessage("识别文字已保存") }, enabled = !busy) { Text("保存") }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OcrLanguage.entries.forEach {
                    FilterChip(language == it, { language = it }, label = { Text(it.label) }, enabled = !busy)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        val recognized = runCatching {
                            val image = withContext(Dispatchers.Default) {
                                AppGraph.codec.decode(repository.readBytes(repository.pagePath(documentId, page)), ScanLimits.MAX_EDGE)
                            }
                            createOcrEngine().recognize(image, language)
                        }
                        busy = false
                        recognized.fold({
                            result = it
                            text = it.text
                            repository.setOcr(documentId, pageId, it)
                            if (text.isBlank()) onMessage("没有识别到文字")
                        }, { onMessage(it.message ?: "离线文字识别失败") })
                    }
                }, enabled = !busy) { Text("识别本页") }
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        var done = 0
                        val recognized = runCatching {
                            val engine = createOcrEngine()
                            document.pages.forEach { item ->
                                val image = withContext(Dispatchers.Default) {
                                    AppGraph.codec.decode(repository.readBytes(repository.pagePath(documentId, item)), ScanLimits.MAX_EDGE)
                                }
                                val ocr = engine.recognize(image, language)
                                repository.setOcr(documentId, item.id, ocr)
                                if (item.id == pageId) { text = ocr.text; result = ocr }
                                done++
                            }
                        }
                        busy = false
                        recognized.fold({ onMessage("已识别 $done 页") }, { onMessage("已完成 $done 页：${it.message ?: "识别失败"}") })
                    }
                }, enabled = !busy) { Text("识别整份文档") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)); onMessage("已复制") }, enabled = !busy && text.isNotBlank()) { Text("复制") }
                TextButton(onClick = {
                    runCatching { persist(); repository.exportText(documentId) }
                        .fold({ share(it, "text/plain", document.title) }, { onMessage(it.message ?: "导出失败") })
                }, enabled = !busy && text.isNotBlank()) { Text("导出 TXT") }
            }
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                label = { Text("识别文字（可编辑）") }, readOnly = busy)
        }
        BusyOverlay(busy, "正在本机识别文字")
    }
}
