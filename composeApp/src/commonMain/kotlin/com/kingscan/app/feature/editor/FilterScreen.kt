package com.kingscan.app.feature.editor

import com.kingscan.app.core.ui.ScanTopBar
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.core.ui.RgbaImageView
import com.kingscan.app.feature.scan.finishScanSave
import com.kingscan.app.navigation.Navigator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.kingscan.app.core.ui.ScanButton as Button
import com.kingscan.app.core.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.core.ui.ScanSecondaryButton as OutlinedButton
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanTextButton as TextButton
import com.kingscan.app.core.ui.ScanSlider as Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.core.storage.defaultScanTitle
import com.kingscan.app.feature.scan.saveScanPages
import com.kingscan.app.feature.scan.writeAndPublish
import com.kingscan.app.core.imaging.PageRenderer
import com.kingscan.app.core.imaging.PageStage
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.imaging.ScanLimits
import com.kingscan.app.core.document.PageEdit
import com.kingscan.app.core.document.FilterType
import com.kingscan.app.core.document.ImageAdjustments
import com.kingscan.app.core.document.ScanMode
import com.kingscan.app.core.document.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

@Composable
fun FilterScreen(
    navigator: Navigator,
    onMessage: (String) -> Unit,
) {
    val warped = AppGraph.session.warped
    if (warped == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有可处理的页面", color = Color.White)
        }
        return
    }
    val previewBase = remember(warped) { warped.scaledDown(ScanLimits.PREVIEW_EDGE) }
    var filter by remember { mutableStateOf(AppGraph.session.edit.filter) }
    var preview by remember { mutableStateOf<RgbaImage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adjustments by remember { mutableStateOf(AppGraph.session.edit.adjustments) }
    var adjustment by remember { mutableStateOf(0) }
    var compare by remember { mutableStateOf(false) }

    LaunchedEffect(filter, previewBase, adjustments) {
        preview = withContext(Dispatchers.Default) {
            PageRenderer.base(previewBase, PageEdit(filter = filter, adjustments = adjustments), PageStage.Retouched)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScanTopBar(
            title = "滤镜",
            onBack = { if (!busy) navigator.pop() },
            contentColor = Color.White,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TextButton(onClick = { compare = !compare }) { Text(if (compare) "查看处理结果" else "对比处理前") }
            TextButton(onClick = { adjustments = ImageAdjustments() }) { Text("重置调整") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val shown = if (compare) previewBase else preview
            if (shown == null) {
                Text("正在生成预览", color = Color.White)
            } else {
                RgbaImageView(shown, Modifier.fillMaxSize(), contentDescription = filter.label())
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("亮度", "对比度", "饱和度").forEachIndexed { index, label ->
                FilterChip(adjustment == index, { adjustment = index }, { Text(label) })
            }
        }
        Slider(
            value = when (adjustment) { 0 -> adjustments.brightness; 1 -> adjustments.contrast; else -> adjustments.saturation },
            onValueChange = { value ->
                adjustments = when (adjustment) {
                    0 -> adjustments.copy(brightness = value)
                    1 -> adjustments.copy(contrast = value)
                    else -> adjustments.copy(saturation = value)
                }
            }, valueRange = -1f..1f, enabled = !busy, modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(FilterType.entries) { item ->
                FilterChip(
                    selected = filter == item,
                    onClick = { if (!busy) filter = item },
                    label = { Text(item.label()) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { savePage(warped, filter, adjustments, continueScan = true, { busy = it }, navigator, onMessage) },
                enabled = !busy && AppGraph.session.editingPageId == null && AppGraph.session.pendingImports.isEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("再拍一页") }
            Button(
                onClick = { savePage(warped, filter, adjustments, continueScan = false, { busy = it }, navigator, onMessage) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(when {
                AppGraph.session.pendingImports.isNotEmpty() -> "保存并编辑下一张"
                AppGraph.session.editingPageId == null && AppGraph.session.scanMode == ScanMode.IdCard ->
                    if (AppGraph.session.idCardPageIds.isEmpty()) "保存并拍反面" else "生成证件复印件"
                else -> "完成"
            }) }
        }
    }
    BusyOverlay(busy, "正在保存")
}

private fun savePage(
    warped: RgbaImage,
    filter: FilterType,
    adjustments: ImageAdjustments,
    continueScan: Boolean,
    setBusy: (Boolean) -> Unit,
    navigator: Navigator,
    onMessage: (String) -> Unit,
) {
    setBusy(true)
    val session = AppGraph.session
    val existing = session.documentId
    val editing = session.editingPageId
    val original = session.originalBytes.takeIf { editing == null }
    val edit = session.edit.copy(quad = session.quad, quarterTurns = session.quarterTurns,
        filter = filter, adjustments = adjustments, marks = emptyList(), retouches = emptyList())
    val mode = session.scanMode
    session.launchProcessing(navigator.scope) {
        try {
            writeAndPublish(
                work = { checkActive ->
                    checkActive()
                    val encoded = PageRenderer.encode(AppGraph.codec, warped, edit, PageStage.Geometry, original)
                    checkActive()
                    saveScanPages(AppGraph.repository, existing, defaultScanTitle(), listOf(encoded), editing)
                },
                rollback = { it.rollback(AppGraph.repository) },
                publish = { result ->
                    finishScanSave(result, mode, navigator, setBusy, onMessage, continueScan)
                },
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { onMessage(failure.message ?: "保存失败") }
        finally {
            if (session.processingJob == coroutineContext[Job]) setBusy(false)
        }
    }
}
