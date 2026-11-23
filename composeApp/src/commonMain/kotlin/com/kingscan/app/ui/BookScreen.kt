package com.kingscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import com.kingscan.app.ui.ScanTextButton as TextButton
import com.kingscan.app.ui.ScanButton as Button
import com.kingscan.app.ui.ScanSecondaryButton as OutlinedButton
import com.kingscan.app.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.ui.ScanSlider as Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.data.defaultScanTitle
import com.kingscan.app.imaging.*
import com.kingscan.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BookScreen(navigator: Navigator, onMessage: (String) -> Unit) {
    val session = AppGraph.session
    val source = session.source ?: return
    val spread = session.warped ?: return
    val initial = session.edit.book
    val seam = remember(spread) { initial?.seamTop ?: BookGeometry.estimateSeam(spread) }
    var seamTop by remember { mutableStateOf(initial?.seamTop ?: seam) }
    var seamBottom by remember { mutableStateOf(initial?.seamBottom ?: seam) }
    var left by remember { mutableStateOf(initial?.takeUnless { it.rightPage } ?: BookPageEdit(false, seam, seam)) }
    var right by remember { mutableStateOf(initial?.takeIf { it.rightPage } ?: BookPageEdit(true, seam, seam)) }
    var activeRight by remember { mutableStateOf(initial?.rightPage ?: false) }
    var rightFirst by remember { mutableStateOf(AppGraph.repository.settings().bookRightToLeft) }
    var filter by remember { mutableStateOf(session.edit.filter) }
    var preview by remember { mutableStateOf<List<RgbaImage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun edits(): List<BookPageEdit> {
        val pages = listOf(left.copy(seamTop = seamTop, seamBottom = seamBottom),
            right.copy(seamTop = seamTop, seamBottom = seamBottom))
        return if (initial != null) pages.filter { it.rightPage == initial.rightPage }
        else if (rightFirst) pages.reversed() else pages
    }
    LaunchedEffect(left, right, seamTop, seamBottom, rightFirst, filter) {
        val parameters = edits()
        preview = withContext(Dispatchers.Default) {
            parameters.map {
                var page = BookGeometry.flatten(spread.scaledDown(1000), it)
                repeat(session.edit.outputQuarterTurns.mod(4)) { page = page.rotate90CW() }
                ImageFilters.apply(page, filter)
            }
        }
    }
    fun save(continueScan: Boolean) {
        val parameters = edits()
        val pageEdit = session.edit.copy(quad = session.quad, quarterTurns = session.quarterTurns,
            filter = filter, marks = emptyList())
        val original = session.originalBytes
        val id = session.documentId
        val editing = session.editingPageId
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val pages = parameters.map { book ->
                        val edit = pageEdit.copy(book = book, retouches = emptyList())
                        val output = PageMarkup.adjust(ImageFilters.apply(PageGeometry.apply(source, edit), filter), edit.adjustments)
                        EncodedPage(AppGraph.codec.encodeJpeg(output, ScanLimits.JPEG_QUALITY),
                            output.width, output.height, filter, originalBytes = original.takeIf { editing == null }, edit = edit)
                    }
                    when {
                        id != null && editing != null -> AppGraph.repository.replacePage(id, editing, pages.single()) ?: error("页面不存在")
                        id == null -> AppGraph.repository.create(defaultScanTitle(), pages)
                        else -> AppGraph.repository.addPages(id, pages)
                    }
                }
            }
            busy = false
            result.fold({ saved ->
                session.documentId = saved.id
                if (editing == null) session.capturedPages += parameters.size
                if (editing == null) AppGraph.notifyScanSaved(saved, saved.pages.takeLast(parameters.size))
                session.clearImage()
                AppGraph.repository.saveSettings(AppGraph.repository.settings().copy(bookRightToLeft = rightFirst))
                if (session.pendingImports.isNotEmpty()) {
                    navigator.finishEditor()
                    processNextImport({ busy = it }, navigator, onMessage)
                } else if (continueScan) navigator.backToCapture() else navigator.showDetail(saved.id)
            }, { onMessage(it.message ?: "保存失败") })
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column {
            ScanTopBar(if (initial == null) "书籍双页" else "书页曲面", { if (!busy) navigator.pop() }, contentColor = Color.White)
            Row(Modifier.fillMaxWidth().weight(1f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.forEachIndexed { index, image ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (initial != null) (if (initial.rightPage) "右页 B" else "左页 A")
                            else if ((index == 0) == rightFirst) "右页 B" else "左页 A", color = Color.White)
                        RgbaImageView(image, Modifier.weight(1f).fillMaxWidth(), contentDescription = "展平预览")
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("中缝顶部", color = Color.White)
                Slider(seamTop, { seamTop = it }, enabled = !busy, valueRange = 0.2f..0.8f)
                Text("中缝底部", color = Color.White)
                Slider(seamBottom, { seamBottom = it }, enabled = !busy, valueRange = 0.2f..0.8f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (initial == null) {
                        FilterChip(!activeRight, { activeRight = false }, { Text("左页 A") })
                        FilterChip(activeRight, { activeRight = true }, { Text("右页 B") })
                        TextButton(onClick = { rightFirst = !rightFirst }) {
                            Text(if (rightFirst) "右 → 左" else "左 → 右")
                        }
                    }
                    TextButton(onClick = {
                        if (activeRight) right = right.copy(topCurve = 0f, bottomCurve = 0f)
                        else left = left.copy(topCurve = 0f, bottomCurve = 0f)
                    }) { Text("重置曲线") }
                }
                val active = if (activeRight) right else left
                Text("顶部弯曲：调整到文字行平直", color = Color.White)
                Slider(active.topCurve, { if (activeRight) right = right.copy(topCurve = it) else left = left.copy(topCurve = it) },
                    enabled = !busy, valueRange = -0.18f..0.18f)
                Text("底部弯曲", color = Color.White)
                Slider(active.bottomCurve, { if (activeRight) right = right.copy(bottomCurve = it) else left = left.copy(bottomCurve = it) },
                    enabled = !busy, valueRange = -0.18f..0.18f)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FilterType.entries) { item -> FilterChip(filter == item, { filter = item }, { Text(item.label()) }) }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (initial == null) OutlinedButton({ save(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("保存并继续拍") }
                Button({ save(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (initial == null) "保存两页" else "保存书页") }
            }
        }
        BusyOverlay(busy, "正在展平并保存")
    }
}
