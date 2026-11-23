package com.kingscan.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kingscan.app.ui.ScanButton as Button
import com.kingscan.app.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.ui.ScanTheme as MaterialTheme
import com.kingscan.app.ui.ScanSecondaryButton as OutlinedButton
import androidx.compose.material3.Text
import com.kingscan.app.ui.ScanTextButton as TextButton
import com.kingscan.app.ui.ScanSlider as Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.data.defaultScanTitle
import com.kingscan.app.imaging.ImageFilters
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.imaging.PageMarkup
import com.kingscan.app.imaging.ScanLimits
import com.kingscan.app.model.EncodedPage
import com.kingscan.app.model.FilterType
import com.kingscan.app.model.ImageAdjustments
import com.kingscan.app.model.ScanMode
import com.kingscan.app.model.Screen
import com.kingscan.app.model.CompositionKind
import com.kingscan.app.model.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(filter, previewBase, adjustments) {
        preview = withContext(Dispatchers.Default) { PageMarkup.adjust(ImageFilters.apply(previewBase, filter), adjustments) }
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
                onClick = { savePage(warped, filter, adjustments, continueScan = true, scope, { busy = it }, navigator, onMessage) },
                enabled = !busy && AppGraph.session.editingPageId == null && AppGraph.session.pendingImports.isEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("再拍一页") }
            Button(
                onClick = { savePage(warped, filter, adjustments, continueScan = false, scope, { busy = it }, navigator, onMessage) },
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
    scope: kotlinx.coroutines.CoroutineScope,
    setBusy: (Boolean) -> Unit,
    navigator: Navigator,
    onMessage: (String) -> Unit,
) {
    setBusy(true)
    scope.launch {
        val saved = runCatching {
            val encoded = withContext(Dispatchers.Default) {
                val filtered = PageMarkup.adjust(ImageFilters.apply(warped, filter), adjustments)
                EncodedPage(
                    jpeg = AppGraph.codec.encodeJpeg(filtered, ScanLimits.JPEG_QUALITY),
                    width = filtered.width,
                    height = filtered.height,
                    filter = filter,
                    originalBytes = AppGraph.session.originalBytes.takeIf { AppGraph.session.editingPageId == null },
                    edit = AppGraph.session.edit.copy(
                        quad = AppGraph.session.quad,
                        quarterTurns = AppGraph.session.quarterTurns,
                        filter = filter,
                        adjustments = adjustments,
                        // A changed crop invalidates annotations in corrected-page coordinates.
                        marks = emptyList(),
                        retouches = emptyList(),
                    ),
                )
            }
            val existing = AppGraph.session.documentId
            val editing = AppGraph.session.editingPageId
            if (existing != null && editing != null) {
                AppGraph.repository.replacePage(existing, editing, encoded) ?: error("页面不存在")
            } else if (existing == null) {
                AppGraph.repository.create(defaultScanTitle(), listOf(encoded))
            } else {
                AppGraph.repository.addPages(existing, listOf(encoded))
            }
        }.getOrNull()
        setBusy(false)
        if (saved == null) {
            onMessage("保存失败")
            return@launch
        }
        AppGraph.session.documentId = saved.id
        if (AppGraph.session.editingPageId == null) AppGraph.notifyScanSaved(saved, listOf(saved.pages.last()))
        val idCard = AppGraph.session.editingPageId == null && AppGraph.session.scanMode == ScanMode.IdCard
        if (AppGraph.session.editingPageId == null) AppGraph.session.capturedPages++
        if (idCard) AppGraph.session.idCardPageIds += saved.pages.last().id
        AppGraph.session.clearImage()
        if (AppGraph.session.pendingImports.isNotEmpty()) {
            navigator.finishEditor()
            processNextImport(setBusy, navigator, onMessage)
        } else if (idCard && AppGraph.session.idCardPageIds.size < 2) navigator.backToCapture()
        else if (idCard) {
            navigator.showDetail(saved.id)
            navigator.push(Screen.ComposePages(saved.id, AppGraph.session.idCardPageIds.takeLast(2), CompositionKind.IdCard))
        } else if (continueScan) navigator.backToCapture() else navigator.showDetail(saved.id)
    }
}
