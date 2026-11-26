package com.kingscan.app.feature.ocr

import com.kingscan.app.core.ui.ScanTopBar
import com.kingscan.app.core.platform.rememberShareLauncher
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.feature.editor.rememberEditorDocument
import com.kingscan.app.navigation.Navigator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.kingscan.app.core.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.core.ui.ScanTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanTextButton as TextButton
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
import com.kingscan.app.core.document.OcrResult
import com.kingscan.app.core.ocr.OcrLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun OcrScreen(documentId: String, pageId: String, navigator: Navigator, onMessage: (String) -> Unit) {
    val repository = AppGraph.repository
    val document = rememberEditorDocument(documentId).value ?: return
    val page = remember(pageId) { document.pages.firstOrNull { it.id == pageId } } ?: return
    var text by remember { mutableStateOf(page.ocr?.text.orEmpty()) }
    var result by remember { mutableStateOf(page.ocr) }
    var busy by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(OcrLanguage.Chinese) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val share = rememberShareLauncher(onMessage)
    fun recognize(allPages: Boolean) {
        val snapshot = language
        scope.launch {
            busy = true
            var done = 0
            try {
                AppGraph.ocr.recognize(documentId, snapshot, pageId.takeUnless { allPages },
                    onSaved = { item, ocr ->
                        if (item.id == pageId) { text = ocr.text; result = ocr }
                        done++
                    })
                if (allPages) onMessage("已识别 $done 页")
                else if (text.isBlank()) onMessage("没有识别到文字")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                onMessage(if (allPages) "已完成 $done 页：${failure.message ?: "识别失败"}"
                    else failure.message ?: "离线文字识别失败")
            } finally { busy = false }
        }
    }
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
                TextButton(onClick = { recognize(false) }, enabled = !busy) { Text("识别本页") }
                TextButton(onClick = { recognize(true) }, enabled = !busy) { Text("识别整份文档") }
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
