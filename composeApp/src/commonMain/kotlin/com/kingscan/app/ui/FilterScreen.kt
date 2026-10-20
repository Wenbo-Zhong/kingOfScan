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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.kingscan.app.imaging.ScanLimits
import com.kingscan.app.model.EncodedPage
import com.kingscan.app.model.FilterType
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
    var filter by remember { mutableStateOf(FilterType.Enhance) }
    var preview by remember { mutableStateOf<RgbaImage?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(filter, previewBase) {
        preview = withContext(Dispatchers.Default) { ImageFilters.apply(previewBase, filter) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScanTopBar(
            title = "滤镜",
            onBack = { if (!busy) navigator.pop() },
            contentColor = Color.White,
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val shown = preview
            if (shown == null) {
                Text("正在生成预览", color = Color.White)
            } else {
                RgbaImageView(shown, Modifier.fillMaxSize(), contentDescription = filter.label())
            }
        }
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
                onClick = { savePage(warped, filter, continueScan = true, scope, { busy = it }, navigator, onMessage) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) { Text("再拍一页") }
            Button(
                onClick = { savePage(warped, filter, continueScan = false, scope, { busy = it }, navigator, onMessage) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) { Text("完成") }
        }
    }
    BusyOverlay(busy, "正在保存")
}

private fun savePage(
    warped: RgbaImage,
    filter: FilterType,
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
                val filtered = ImageFilters.apply(warped, filter)
                EncodedPage(
                    jpeg = AppGraph.codec.encodeJpeg(filtered, ScanLimits.JPEG_QUALITY),
                    width = filtered.width,
                    height = filtered.height,
                    filter = filter,
                )
            }
            val existing = AppGraph.session.documentId
            if (existing == null) {
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
        AppGraph.session.clearImage()
        if (continueScan) navigator.backToCapture() else navigator.showDetail(saved.id)
    }
}
