package com.kingscan.app.feature.editor

import com.kingscan.app.core.ui.ScanTopBar
import com.kingscan.app.core.ui.ScanIconButton
import com.kingscan.app.core.ui.ScanIcon
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.core.ui.RgbaImageView
import com.kingscan.app.core.ui.ScanSymbol
import com.kingscan.app.navigation.Navigator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanTheme as MaterialTheme
import com.kingscan.app.core.ui.ScanTextButton as TextButton
import com.kingscan.app.core.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.core.ui.ScanSlider as Slider
import com.kingscan.app.core.ui.ScanCheckbox as Checkbox
import com.kingscan.app.core.ui.ScanDialog as AlertDialog
import com.kingscan.app.core.ui.ScanTextField as OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.core.imaging.EditHistory
import com.kingscan.app.core.imaging.PageMarkup
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.imaging.ScanLimits
import com.kingscan.app.core.document.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Composable
fun AnnotationScreen(documentId: String, pageId: String, navigator: Navigator, onMessage: (String) -> Unit) {
    val repository = AppGraph.repository
    val document = rememberEditorDocument(documentId).value ?: return
    val page = remember(pageId) { document.pages.firstOrNull { it.id == pageId } } ?: return
    val history = remember(pageId) { EditHistory(page.edit?.marks.orEmpty()) }
    var marks by remember { mutableStateOf(history.current) }
    var tool by remember { mutableStateOf(MarkType.Pen) }
    var color by remember { mutableStateOf(0xFF202020L) }
    var width by remember { mutableFloatStateOf(0.005f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var base by remember { mutableStateOf<RgbaImage?>(null) }
    var preview by remember { mutableStateOf<RgbaImage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var livePoints by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var text by remember { mutableStateOf("仅供办理业务使用") }
    var textDialog by remember { mutableStateOf(false) }
    var signatureDialog by remember { mutableStateOf(false) }
    var stamp by remember { mutableStateOf<SavedSignature?>(null) }
    var allPages by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun append(mark: PageMark) { history.push(marks + mark); marks = history.current }
    val appendLatest by rememberUpdatedState<(PageMark) -> Unit>(::append)
    val appendGroupLatest by rememberUpdatedState<(List<PageMark>) -> Unit>({ group ->
        history.push(marks + group); marks = history.current
    })
    LaunchedEffect(pageId) {
        val result = withContext(Dispatchers.Default) {
            runCatching { AppGraph.codec.decode(repository.readBytes(repository.annotationBasePath(documentId, page)), ScanLimits.PREVIEW_EDGE) }
        }
        loading = false
        result.fold({ base = it }, { onMessage(it.message ?: "读取页面失败") })
    }
    LaunchedEffect(base, marks) {
        base?.let { image -> preview = withContext(Dispatchers.Default) { PageMarkup.render(image, marks) } }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScanTopBar("批注与签名", onBack = { if (!busy) navigator.pop() }) {
                ScanIconButton(onClick = { marks = history.undo() }, enabled = history.canUndo && !busy) { ScanIcon(ScanSymbol.Undo, "撤销") }
                ScanIconButton(onClick = { marks = history.redo() }, enabled = history.canRedo && !busy) { ScanIcon(ScanSymbol.Redo, "重做") }
                TextButton(onClick = {
                    val snapshot = marks
                    scope.launch {
                        busy = true
                        var completed = 0
                        val result = withContext(Dispatchers.Default) {
                            runCatching {
                                saveAnnotations(repository, AppGraph.codec, document, page, snapshot, allPages) { completed++ }
                            }
                        }
                        busy = false
                        result.fold({ navigator.pop() }, { onMessage("已保存 $completed 页：${it.message ?: "保存失败"}") })
                    }
                }, enabled = !busy && base != null) { Text("保存") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF242629)), contentAlignment = Alignment.Center) {
                val image = preview ?: base
                if (image != null) {
                    val ratio = image.width.toFloat() / image.height
                    val viewWidth = minOf(maxWidth, maxHeight * ratio)
                    Box(Modifier.size(viewWidth, viewWidth / ratio)) {
                        RgbaImageView(image, Modifier.fillMaxSize(), "批注预览")
                        Canvas(Modifier.fillMaxSize().pointerInput(tool, color, width, opacity, text, stamp) {
                            fun point(offset: Offset) = PointF(offset.x / size.width, offset.y / size.height).clamped()
                            if (tool == MarkType.Text || stamp != null) {
                                detectTapGestures { offset ->
                                    val anchor = point(offset)
                                    val signature = stamp
                                    if (signature != null) {
                                        val paths = signature.paths.ifEmpty { listOf(signature.points) }
                                        val placementWidth = minOf(0.35f, 1f - anchor.x)
                                        val placementHeight = minOf(placementWidth * ratio / signature.aspectRatio.coerceAtLeast(0.1f), 1f - anchor.y)
                                        appendGroupLatest(paths.map { path ->
                                            PageMark(Random.nextLong().toString(), MarkType.Signature,
                                                path.map { PointF(anchor.x + it.x * placementWidth, anchor.y + it.y * placementHeight) },
                                                color = color, strokeWidth = signature.strokeWidth, opacity = opacity)
                                        })
                                    } else {
                                        appendLatest(PageMark(Random.nextLong().toString(), tool, listOf(anchor),
                                            text, color, width.coerceAtLeast(0.025f), opacity))
                                    }
                                }
                            } else {
                                detectDragGestures(
                                    onDragStart = { livePoints = listOf(point(it)) },
                                    onDragCancel = { livePoints = emptyList() },
                                    onDragEnd = {
                                        if (livePoints.isNotEmpty()) appendLatest(PageMark(Random.nextLong().toString(), tool,
                                            livePoints, color = color, strokeWidth = width, opacity = opacity))
                                        livePoints = emptyList()
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        livePoints = if (tool == MarkType.Redaction) listOf(livePoints.firstOrNull() ?: point(change.position), point(change.position))
                                            else livePoints + point(change.position)
                                    },
                                )
                            }
                        }) {
                            if (livePoints.isNotEmpty()) {
                                val first = livePoints.first()
                                if (tool == MarkType.Redaction && livePoints.size >= 2) {
                                    val last = livePoints.last()
                                    drawRect(Color.Black, Offset(minOf(first.x, last.x) * size.width, minOf(first.y, last.y) * size.height),
                                        Size(kotlin.math.abs(last.x - first.x) * size.width, kotlin.math.abs(last.y - first.y) * size.height))
                                } else {
                                    val path = Path().apply {
                                        moveTo(first.x * size.width, first.y * size.height)
                                        livePoints.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                                    }
                                    drawPath(path, Color(color).copy(alpha = opacity), style = Stroke(width * minOf(size.width, size.height)))
                                }
                            }
                        }
                    }
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MarkType.entries) { item ->
                    FilterChip(tool == item && stamp == null, {
                        tool = item; stamp = null
                        if (tool == MarkType.Text) { width = 0.035f; opacity = 0.45f; textDialog = true }
                        else { width = 0.005f; opacity = 1f }
                    }, { Text(when (item) { MarkType.Pen -> "笔迹"; MarkType.Signature -> "签名"; MarkType.Text -> "文字水印"; MarkType.Redaction -> "遮盖" }) })
                }
                item { TextButton(onClick = { history.push(emptyList()); marks = history.current }) { Text("清空") } }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0xFF202020L, 0xFF1753C6L, 0xFFD62828L).forEach { value ->
                    FilterChip(color == value, { color = value }, { Text("●", color = Color(value)) })
                }
                if (tool == MarkType.Text) TextButton(onClick = { textDialog = true }) { Text("编辑文字") }
                if (tool == MarkType.Signature) {
                    TextButton(onClick = {
                        val signatures = marks.takeLastWhile { it.type == MarkType.Signature }
                        if (signatures.isEmpty()) onMessage("请先书写签名")
                        else runCatching { repository.saveSignature("签名 ${repository.signatures().size + 1}", signatures, page.width.toFloat() / page.height) }
                            .fold({ onMessage("签名已保存") }, { onMessage(it.message ?: "保存失败") })
                    }) { Text("存为签名") }
                    TextButton(onClick = { signatureDialog = true }) { Text("我的签名") }
                }
            }
            Text(if (stamp != null) "点击页面放置签名" else if (tool == MarkType.Text) "点击页面放置文字" else "在页面上拖动绘制",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tool == MarkType.Text) "字号" else "粗细")
                Slider(width, { width = it }, valueRange = if (tool == MarkType.Text) 0.015f..0.1f else 0.002f..0.02f, modifier = Modifier.weight(1f))
                Text("透明度")
                Slider(opacity, { opacity = it }, valueRange = 0.1f..1f, modifier = Modifier.weight(1f), enabled = tool != MarkType.Redaction)
            }
            if (tool == MarkType.Text && document.pages.size > 1) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allPages, { allPages = it }); Text("应用到文档全部页面")
                }
            }
        }
        BusyOverlay(loading || busy, if (busy) "正在保存批注" else "正在读取页面")
    }
    if (textDialog) AlertDialog(onDismissRequest = { textDialog = false }, title = { Text("文字水印") },
        text = { OutlinedTextField(text, { text = it }, minLines = 2) },
        confirmButton = { TextButton(onClick = { textDialog = false }) { Text("在页面上放置") } })
    if (signatureDialog) AlertDialog(onDismissRequest = { signatureDialog = false }, title = { Text("我的签名") },
        text = {
            Column {
                val signatures = repository.signatures()
                if (signatures.isEmpty()) Text("书写签名后，点击「存为签名」")
                signatures.forEach { saved ->
                    TextButton(onClick = { stamp = saved; signatureDialog = false }) { Text(saved.name) }
                }
            }
        }, confirmButton = { TextButton(onClick = { signatureDialog = false }) { Text("关闭") } })
}
