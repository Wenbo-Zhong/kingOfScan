package com.kingscan.app.feature.scan

import com.kingscan.app.core.platform.ScannerCamera
import com.kingscan.app.core.platform.rememberImagesImporter
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.core.ui.ScanLinearProgress
import com.kingscan.app.core.ui.ScanSymbol
import com.kingscan.app.core.ui.Seal
import com.kingscan.app.navigation.Navigator
import com.kingscan.app.navigation.Screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanTextButton as TextButton
import com.kingscan.app.core.ui.ScanIconButton as IconButton
import com.kingscan.app.core.ui.ScanIcon as Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.core.storage.defaultScanTitle
import com.kingscan.app.core.imaging.*
import com.kingscan.app.core.document.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CaptureScreen(navigator: Navigator, onMessage: (String) -> Unit) {
    val settings = remember { AppGraph.repository.settings() }
    var mode by remember { mutableStateOf(settings.scanMode) }
    var automatic by remember { mutableStateOf(settings.autoCapture) }
    var continuous by remember { mutableStateOf(settings.continuousCapture) }
    var torch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<(() -> Unit)?>(null) }
    var frame by remember { mutableStateOf<CaptureFrame?>(null) }
    var readiness by remember { mutableStateOf(CaptureReadiness(0f, "对齐后点击拍摄")) }
    var count by remember { mutableStateOf(AppGraph.session.capturedPages) }
    val controller = AppGraph.session.captureController
    val captureGeneration = remember { AppGraph.session.generation }
    fun persist() {
        AppGraph.session.scanMode = mode
        AppGraph.repository.saveSettings(AppGraph.repository.settings().copy(
            scanMode = mode, autoCapture = automatic, continuousCapture = continuous))
    }
    fun shutter() {
        if (busy || AppGraph.session.processing || AppGraph.session.transitioning || capture == null) return
        busy = true
        controller.markCaptured(frame)
        capture?.invoke()
    }
    val importImage = rememberImagesImporter(
        onImages = { files -> importFiles(files, { busy = it }, navigator, onMessage) },
        onError = { busy = false; onMessage(it) }, onStart = { busy = true }, onCancel = { busy = false },
    )
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ScannerCamera(
            torchEnabled = torch, modifier = Modifier.fillMaxSize(), onBind = { capture = it },
            onPhoto = { bytes ->
                if (navigator.current == Screen.Capture && !AppGraph.session.transitioning &&
                    captureGeneration == AppGraph.session.generation) {
                    AppGraph.session.scanMode = mode
                    if (continuous || mode == ScanMode.LargeFormat) saveContinuousCapture(bytes, mode, { busy = it }, navigator, onMessage) {
                        count = AppGraph.session.capturedPages
                    } else openEditor(bytes, { busy = it }, navigator, onMessage, mode)
                }
            },
            onError = {
                if (captureGeneration == AppGraph.session.generation) {
                    busy = false; controller.reset(); onMessage(it)
                }
            },
            onDetection = { next ->
                if (captureGeneration == AppGraph.session.generation && !AppGraph.session.transitioning &&
                    navigator.current == Screen.Capture) {
                    frame = next
                    readiness = controller.update(next, automatic && mode != ScanMode.LargeFormat && capture != null, busy)
                    if (readiness.capture) shutter()
                }
            },
        )
        frame?.let { analyzed ->
            Canvas(Modifier.fillMaxSize()) {
                val ratio = analyzed.width.toFloat() / analyzed.height
                val contentWidth = minOf(size.width, size.height * ratio)
                val contentHeight = contentWidth / ratio
                val left = (size.width - contentWidth) / 2
                val top = (size.height - contentHeight) / 2
                fun map(point: PointF) = Offset(left + point.x * contentWidth, top + point.y * contentHeight)
                analyzed.quad?.let { quad ->
                    val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                    val path = Path().apply {
                        val first = map(points.first()); moveTo(first.x, first.y)
                        points.drop(1).forEach { val p = map(it); lineTo(p.x, p.y) }
                        close()
                    }
                    drawPath(path, Seal.copy(alpha = 0.15f))
                    drawPath(path, Seal, style = Stroke(3.dp.toPx()))
                    points.forEach { drawCircle(Color.White, 4.dp.toPx(), map(it)) }
                    if (mode == ScanMode.Book) {
                        val upper = PointF((quad.topLeft.x + quad.topRight.x) / 2, (quad.topLeft.y + quad.topRight.y) / 2)
                        val lower = PointF((quad.bottomLeft.x + quad.bottomRight.x) / 2, (quad.bottomLeft.y + quad.bottomRight.y) / 2)
                        drawLine(Color.White, map(upper), map(lower), 2.dp.toPx())
                    }
                }
                if (mode == ScanMode.IdCard && analyzed.quad == null) {
                    val width = contentWidth * 0.85f; val height = width / 1.585f
                    drawRect(Color.White, Offset((size.width - width) / 2, (size.height - height) / 2),
                        androidx.compose.ui.geometry.Size(width, height), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (!busy) {
                        val id = AppGraph.session.documentId
                        navigator.scope.launch {
                            AppGraph.session.cancel()
                            if (id == null) navigator.pop() else navigator.showDetail(id)
                        }
                    }
                }, enabled = !busy) { Icon(ScanSymbol.Close, "关闭", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { automatic = !automatic; controller.reset(); persist() }, enabled = !busy && mode != ScanMode.LargeFormat) {
                    Text(if (automatic) "自动拍摄 开" else "自动拍摄 关", color = Color.White)
                }
                IconButton(onClick = { torch = !torch }) {
                    Icon(if (torch) ScanSymbol.FlashOn else ScanSymbol.FlashOff, "补光灯", tint = Color.White)
                }
            }
            Text(when (mode) {
                ScanMode.Document -> readiness.hint
                ScanMode.IdCard -> (if (AppGraph.session.idCardPageIds.size % 2 == 0) "拍摄证件正面" else "拍摄证件反面") + " · " + readiness.hint
                ScanMode.Book -> "横放摊开的书 · " + readiness.hint
                ScanMode.LargeFormat -> "保持方向一致，相邻画面保留约一半重叠，手动拍摄"
            }, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(12.dp))
            if (readiness.progress > 0) ScanLinearProgress(
                progress = { readiness.progress }, color = Seal, modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScanMode.entries.forEach { item ->
                    TextButton(onClick = {
                        if (mode != item) AppGraph.session.idCardPageIds.clear()
                        if (mode != item) AppGraph.session.largeFormatPageIds.clear()
                        mode = item; controller.reset(); persist()
                    }, enabled = !busy) {
                        Text(item.label(), color = if (mode == item) Seal else Color.White)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { continuous = !continuous; persist() }, enabled = !busy && mode != ScanMode.LargeFormat) {
                    Text(if (continuous || mode == ScanMode.LargeFormat) "连续拍摄" else "逐页编辑", color = Color.White)
                }
                Text("$count 页", color = Color.White)
                TextButton(onClick = {
                    AppGraph.session.documentId?.let { id ->
                        if (mode == ScanMode.LargeFormat) {
                            if (AppGraph.session.largeFormatPageIds.size < 2) onMessage("请拍摄至少两张有重叠的画面")
                            else {
                                navigator.showDetail(id)
                                navigator.push(Screen.ComposePages(id, AppGraph.session.largeFormatPageIds.toList(), CompositionKind.Overlap))
                            }
                        } else navigator.showDetail(id)
                    }
                }, enabled = !busy && AppGraph.session.documentId != null) {
                    Text(if (mode == ScanMode.LargeFormat) "完成并拼接" else "完成", color = Seal)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                IconButton(onClick = { if (!busy) importImage() }, modifier = Modifier.size(56.dp)) {
                    Icon(ScanSymbol.PhotoLibrary, "相册", tint = Color.White)
                }
                Box(Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                    .padding(6.dp).clip(CircleShape)
                    .background(if (busy || capture == null) Color.White.copy(alpha = 0.45f) else Color.White)
                    .clickable(enabled = !busy && capture != null) { shutter() })
                Spacer(Modifier.size(56.dp))
            }
        }
        BusyOverlay(busy, "正在处理并保存")
    }
}

private fun saveContinuousCapture(
    bytes: ByteArray, mode: ScanMode, setBusy: (Boolean) -> Unit,
    navigator: Navigator, onMessage: (String) -> Unit, onSaved: () -> Unit,
) {
    setBusy(true)
    val session = AppGraph.session
    val targetId = session.documentId
    val settings = AppGraph.repository.settings()
    session.launchProcessing(navigator.scope) {
        try {
            writeAndPublish(
                work = { checkActive ->
                    checkActive()
                    val image = AppGraph.codec.decode(bytes, ScanLimits.MAX_EDGE)
                    checkActive()
                    val quad = if (mode == ScanMode.LargeFormat) Quad.full()
                        else DocumentDetector.detectOrNull(image) ?: error("未找到完整边缘，请改用逐页编辑")
                    val filter = if (mode == ScanMode.LargeFormat) FilterType.Original else settings.defaultFilter
                    val base = PageEdit(quad = quad, filter = filter)
                    val edits = if (mode == ScanMode.Book) {
                        val spread = PerspectiveWarp.warp(image, quad)
                        val seam = BookGeometry.estimateSeam(spread)
                        val firstRight = settings.bookRightToLeft
                        listOf(firstRight, !firstRight).map { base.copy(book = BookPageEdit(it, seam, seam)) }
                    } else listOf(base)
                    val pages = edits.map { edit ->
                        checkActive()
                        PageRenderer.encode(AppGraph.codec, image, edit, PageStage.OrientedOriginal, bytes)
                    }
                    checkActive()
                    saveScanPages(AppGraph.repository, targetId,
                        if (mode == ScanMode.IdCard) "证件扫描" else defaultScanTitle(), pages)
                },
                rollback = { it.rollback(AppGraph.repository) },
                publish = { result ->
                    finishScanSave(result, mode, navigator, setBusy, onMessage, continuous = true, onSaved = onSaved)
                },
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { onMessage(failure.message ?: "保存失败") }
        finally { setBusy(false) }
    }
}

internal fun openEditor(
    bytes: ByteArray, setBusy: (Boolean) -> Unit, navigator: Navigator,
    onMessage: (String) -> Unit, mode: ScanMode = ScanMode.Document,
) {
    setBusy(true)
    AppGraph.session.launchProcessing(navigator.scope) {
        try {
            val prepared = withContext(Dispatchers.Default) {
                runCatching {
                    val image = AppGraph.codec.decode(bytes, ScanLimits.MAX_EDGE)
                    image to DocumentDetector.detect(image)
                }.getOrNull()
            }
            coroutineContext.ensureActive()
            setBusy(false)
            if (prepared == null) {
                onMessage("这张图片无法读取")
                if (AppGraph.session.pendingImports.isNotEmpty()) processNextImport(setBusy, navigator, onMessage)
                else AppGraph.session.documentId?.let(navigator::showDetail)
                return@launchProcessing
            }
            AppGraph.session.scanMode = mode
            AppGraph.session.source = prepared.first
            AppGraph.session.originalBytes = bytes
            AppGraph.session.editingPageId = null
            AppGraph.session.quarterTurns = 0
            AppGraph.session.edit = PageEdit(filter = AppGraph.repository.settings().defaultFilter)
            AppGraph.session.quad = prepared.second
            AppGraph.session.warped = null
            navigator.push(Screen.Crop)
        } finally {
            if (AppGraph.session.processingJob == coroutineContext[Job]) setBusy(false)
        }
    }
}
