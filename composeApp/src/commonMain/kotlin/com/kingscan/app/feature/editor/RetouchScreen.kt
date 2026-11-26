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
import com.kingscan.app.core.ui.ScanButton as Button
import com.kingscan.app.core.ui.ScanSecondaryButton as OutlinedButton
import com.kingscan.app.core.ui.ScanSlider as Slider
import com.kingscan.app.core.ui.ScanProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.core.imaging.*
import com.kingscan.app.core.document.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
fun RetouchScreen(documentId: String, pageId: String, navigator: Navigator, onMessage: (String) -> Unit) {
    val repository = AppGraph.repository
    val document = rememberEditorDocument(documentId).value ?: return
    val page = remember(pageId) { document.pages.firstOrNull { it.id == pageId } } ?: return
    val hasOriginal = page.originalFileName != null
    val edit = remember(page) {
        if (hasOriginal) page.edit ?: PageEdit(filter = page.filter)
        else PageEdit(filter = FilterType.Original, marks = page.edit?.marks.orEmpty())
    }
    val history = remember { EditHistory(edit.retouches) }
    var effects by remember { mutableStateOf(history.current) }
    var source by remember { mutableStateOf<RgbaImage?>(null) }
    var processed by remember { mutableStateOf<RgbaImage?>(null) }
    var preview by remember { mutableStateOf<RgbaImage?>(null) }
    var strokes by remember { mutableStateOf<List<RetouchStroke>>(emptyList()) }
    var live by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var width by remember { mutableFloatStateOf(0.04f) }
    var strength by remember { mutableFloatStateOf(0.5f) }
    var generating by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val renderMutex = remember { Mutex() }
    val cache = remember { mutableListOf<Pair<List<RetouchEdit>, RgbaImage>>() }
    val scope = rememberCoroutineScope()
    fun append(effect: RetouchEdit) {
        history.push(effects + effect); effects = history.current
    }
    val onStroke by rememberUpdatedState<(List<PointF>) -> Unit>({ points ->
        if (points.isNotEmpty()) strokes = strokes + RetouchStroke(points, width)
    })
    LaunchedEffect(pageId) {
        val result = withContext(Dispatchers.Default) { runCatching { AppGraph.pageOperations.geometricSource(documentId, page, edit) } }
        result.fold({ source = it }, { generating = false; onMessage(it.message ?: "读取失败") })
    }
    LaunchedEffect(source, effects) {
        val input = source ?: return@LaunchedEffect
        val snapshot = effects
        generating = true
        progress = 0f
        val result = withContext(Dispatchers.Default) {
            renderMutex.withLock {
                val context = currentCoroutineContext()
                try {
                    val hit = cache.firstOrNull { it.first == snapshot }
                    val prefix = cache.filter { snapshot.take(it.first.size) == it.first }.maxByOrNull { it.first.size }
                    val restored = hit?.second ?: PageRetouch.apply(prefix?.second ?: input,
                        snapshot.drop(prefix?.first?.size ?: 0), edit.outputQuarterTurns,
                        { progress = it }, { context.ensureActive() })
                    context.ensureActive()
                    if (hit == null) {
                        cache += snapshot to restored
                        while (cache.size > 2) cache.removeAt(0)
                    }
                    Result.success(PageRenderer.base(restored, edit, PageStage.Retouched))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
            }
        }
        result.fold({
            processed = it
            preview = withContext(Dispatchers.Default) { PageMarkup.render(it.scaledDown(ScanLimits.PREVIEW_EDGE), edit.marks) }
        }, {
            onMessage(it.message ?: "处理失败，请撤销")
            if (history.canUndo) effects = history.undo()
            else { processed = null; preview = null }
        })
        generating = false
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScanTopBar("图像修复", onBack = { if (!busy) navigator.pop() }) {
                ScanIconButton(onClick = { strokes = emptyList(); effects = history.undo() }, enabled = !busy && history.canUndo) { ScanIcon(ScanSymbol.Undo, "撤销") }
                ScanIconButton(onClick = { effects = history.redo() }, enabled = !busy && history.canRedo) { ScanIcon(ScanSymbol.Redo, "重做") }
                TextButton(onClick = {
                    val output = processed ?: return@TextButton
                    val snapshot = effects
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            runCatching {
                                repository.replacePage(documentId, pageId, PageRenderer.encode(
                                    AppGraph.codec, output, edit.copy(retouches = snapshot), PageStage.Adjusted,
                                    originalBytes = if (hasOriginal) null else source?.let { AppGraph.codec.encodeJpeg(it, 98) },
                                    retainBase = true,
                                )) ?: error("页面不存在")
                            }
                        }
                        busy = false
                        result.fold({ navigator.pop() }, { onMessage(it.message ?: "保存失败") })
                    }
                }, enabled = !busy && !generating && processed != null && strokes.isEmpty()) { Text("保存") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF242629)), contentAlignment = Alignment.Center) {
                val image = if (compare) source?.scaledDown(ScanLimits.PREVIEW_EDGE) else preview
                if (image != null) {
                    val ratio = image.width.toFloat() / image.height
                    val imageWidth = minOf(maxWidth, maxHeight * ratio)
                    Box(Modifier.size(imageWidth, imageWidth / ratio)) {
                        RgbaImageView(image, Modifier.fillMaxSize(), "修复预览")
                        Canvas(Modifier.fillMaxSize().pointerInput(width, busy, generating, compare) {
                            if (!busy && !generating && !compare) {
                                fun point(p: Offset) = PointF(p.x / size.width, p.y / size.height).clamped()
                                detectDragGestures(
                                    onDragStart = { live = listOf(point(it)) },
                                    onDragCancel = { live = emptyList() },
                                    onDragEnd = { onStroke(live); live = emptyList() },
                                ) { change, _ -> change.consume(); live = live + point(change.position) }
                            }
                        }.pointerInput(width, busy, generating, compare) {
                            if (!busy && !generating && !compare) detectTapGestures { p ->
                                onStroke(listOf(PointF(p.x / size.width, p.y / size.height).clamped()))
                            }
                        }) {
                            (strokes + listOf(RetouchStroke(live, width))).forEach { stroke ->
                                val points = stroke.points
                                if (points.size == 1) drawCircle(Color(0x80FF5050), stroke.width * minOf(size.width, size.height) / 2,
                                    Offset(points[0].x * size.width, points[0].y * size.height))
                                else if (points.size > 1) drawPath(Path().apply {
                                    moveTo(points[0].x * size.width, points[0].y * size.height)
                                    points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                                }, Color(0x80FF5050), style = Stroke(stroke.width * minOf(size.width, size.height)))
                            }
                        }
                    }
                }
                if (generating) Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.background(Color(0xDD242629)).padding(20.dp)) {
                    CircularProgressIndicator()
                    Text("正在处理 ${(progress * 100).toInt()}%", color = Color.White)
                    if (history.canUndo) TextButton(onClick = { effects = history.undo() }) { Text("取消本次处理") }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
                Text("自动识别后请对比检查；遗漏可涂抹补选，误删可撤销。被遮挡文字无法可靠恢复。", style = MaterialTheme.typography.bodySmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(RetouchKind.SmartHd to "智能高清", RetouchKind.Handwriting to "去黑色手写",
                        RetouchKind.Fingers to "去手指", RetouchKind.Watermark to "去水印", RetouchKind.Demoire to "去摩尔纹")) { (kind, label) ->
                        OutlinedButton(onClick = {
                            append(RetouchEdit(kind = kind, strength = strength, orientation = edit.outputQuarterTurns))
                        }, enabled = !busy && !generating && strokes.isEmpty()) { Text(label) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("笔刷")
                    Slider(width, { width = it }, valueRange = 0.01f..0.16f, enabled = !busy && !generating, modifier = Modifier.weight(1f))
                    TextButton(onClick = { compare = !compare }) { Text(if (compare) "查看结果" else "对比原图") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { strokes = emptyList() }, enabled = strokes.isNotEmpty() && !busy) { Text("清除选区") }
                    TextButton(onClick = {
                        strokes = emptyList()
                        history.push(emptyList())
                        effects = history.current
                    }, enabled = effects.isNotEmpty() && !busy && !generating) { Text("重置修复") }
                    Button(onClick = { append(RetouchEdit(strokes = strokes)); strokes = emptyList() },
                        enabled = strokes.isNotEmpty() && !busy && !generating) { Text("擦除选区") }
                }
                Text("修复／识别强度")
                Slider(strength, { strength = it }, enabled = !busy && !generating, valueRange = 0f..1f)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(RetouchKind.ColoredInk to "去彩笔", RetouchKind.Denoise to "降噪", RetouchKind.Deblur to "去轻微失焦").forEach { (kind, label) ->
                        TextButton(onClick = { append(RetouchEdit(kind = kind, strength = strength)) },
                            enabled = !busy && !generating && strokes.isEmpty()) { Text(label) }
                    }
                }
                Text("去水印包含印章处理；去彩笔会移除红蓝彩色内容。", style = MaterialTheme.typography.bodySmall)
            }
        }
        BusyOverlay(busy, "正在保存")
    }
}
