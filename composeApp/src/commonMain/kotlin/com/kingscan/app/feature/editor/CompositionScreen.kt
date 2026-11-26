package com.kingscan.app.feature.editor

import com.kingscan.app.core.ui.ScanTopBar
import com.kingscan.app.core.platform.LocalContentLocked
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.core.ui.JpegThumb
import com.kingscan.app.core.ui.RgbaImageView
import com.kingscan.app.navigation.Navigator
import com.kingscan.app.navigation.Screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanTheme as MaterialTheme
import com.kingscan.app.core.ui.ScanButton as Button
import com.kingscan.app.core.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.core.ui.ScanSlider as Slider
import com.kingscan.app.core.ui.ScanProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.core.imaging.*
import com.kingscan.app.core.document.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun CompositionKind.label(): String = when (this) {
    CompositionKind.Grid -> "A4 拼版"
    CompositionKind.Vertical -> "竖向长图"
    CompositionKind.Horizontal -> "横向长图"
    CompositionKind.IdCard -> "证件复印件"
    CompositionKind.Overlap -> "重叠拼接"
}

@Composable
fun CompositionScreen(screen: Screen.ComposePages, navigator: Navigator, onMessage: (String) -> Unit) {
    val repository = AppGraph.repository
    val document = rememberEditorDocument(screen.documentId).value ?: return
    val rebuild = screen.pageIds.isEmpty() && document.composition != null
    val available = remember(document, rebuild) {
        if (rebuild) document.compositionSourceIds.mapNotNull { id -> document.sourcePages.firstOrNull { it.id == id } }
        else document.pages
    }
    var selected by remember { mutableStateOf(screen.pageIds.ifEmpty { available.map { it.id } }) }
    var options by remember { mutableStateOf(if (rebuild) document.composition!! else CompositionOptions(kind = screen.kind)) }
    var preview by remember { mutableStateOf<RgbaImage?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun inputs(ids: List<String>, edge: Int): List<RgbaImage> = ids.map { id ->
        val page = available.first { it.id == id }
        AppGraph.codec.decode(repository.readBytes(repository.pagePath(document.id, page)), edge)
    }
    LaunchedEffect(selected, options) {
        val ids = selected.toList()
        generating = true
        val result = withContext(Dispatchers.Default) {
            runCatching { PageComposer.compose(inputs(ids, 720), options.copy(outputWidth = 720,
                gap = (options.gap * 720f / options.outputWidth).toInt())) }
        }
        result.fold({ preview = it; previewError = null }, { preview = null; previewError = it.message })
        generating = false
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScanTopBar(if (rebuild) "重新拼版" else "图片拼版与拼接", onBack = { if (!busy) navigator.pop() })
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CompositionKind.entries) { kind ->
                    FilterChip(options.kind == kind, { options = options.copy(kind = kind) }, { Text(kind.label()) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                preview?.let { RgbaImageView(it, Modifier.fillMaxSize(), "合成预览") }
                if (generating) CircularProgressIndicator()
                else previewError?.let { Text(it) }
            }
            LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(available, key = { it.id }) { page ->
                    Column(Modifier.width(80.dp).clickable(enabled = !busy) {
                        selected = if (page.id in selected) selected - page.id else selected + page.id
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!LocalContentLocked.current)
                            JpegThumb(repository.pagePath(document.id, page), Modifier.size(64.dp, 76.dp), "输入页")
                        Text(if (page.id in selected) "顺序 ${selected.indexOf(page.id) + 1}" else "未选择")
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (options.kind == CompositionKind.Grid) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..4).forEach { columns ->
                            FilterChip(options.columns == columns, { options = options.copy(columns = columns) }, { Text("$columns 列") })
                        }
                    }
                }
                if (options.kind != CompositionKind.Overlap && options.kind != CompositionKind.IdCard) {
                    Text("间距 ${options.gap} px")
                    Slider(options.gap.toFloat(), { options = options.copy(gap = it.toInt()) }, valueRange = 0f..80f, enabled = !busy)
                }
                if (options.kind != CompositionKind.Overlap) {
                    Text("输出尺寸 ${options.outputWidth} px")
                    Slider(options.outputWidth.toFloat(), { options = options.copy(outputWidth = it.toInt()) },
                        valueRange = 800f..2400f, steps = 3, enabled = !busy)
                }
                Text(when (options.kind) {
                    CompositionKind.IdCard -> "按选择顺序放置正、反面，A4 上保持 85.6 × 54 mm"
                    CompositionKind.Overlap -> "相邻图需有清晰重叠区域；保持相机方向一致，按拍摄顺序选择"
                    else -> "点击缩略图选择页；取消后重新选择可改变顺序"
                }, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                val ids = selected.toList(); val specification = options
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runCatching {
                            val output = PageComposer.compose(inputs(ids, 1600), specification)
                            val bytes = AppGraph.codec.encodeJpeg(output, 94)
                            repository.composePages(document.id, ids,
                                EncodedPage(bytes, output.width, output.height, FilterType.Original,
                                    edit = PageEdit(filter = FilterType.Original), annotationBaseBytes = bytes), specification)
                        }
                    }
                    busy = false
                    result.fold({ navigator.showDetail(it.id) }, { onMessage(it.message ?: "生成失败") })
                }
            }, enabled = !busy && !generating && preview != null,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("生成文档") }
        }
        BusyOverlay(busy, "正在生成文档")
    }
}
