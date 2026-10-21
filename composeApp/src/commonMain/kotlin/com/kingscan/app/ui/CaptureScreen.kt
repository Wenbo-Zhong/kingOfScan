package com.kingscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.kingscan.app.imaging.DocumentDetector
import com.kingscan.app.imaging.ScanLimits
import com.kingscan.app.model.Quad
import com.kingscan.app.model.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CaptureScreen(
    navigator: Navigator,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var torch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<(() -> Unit)?>(null) }
    var detectedQuad by remember { mutableStateOf<Quad?>(null) }
    val importImage = rememberImageImporter(
        onImage = { bytes -> openEditor(bytes, scope, { busy = it }, navigator, onMessage) },
        onError = { message -> busy = false; onMessage(message) },
        onStart = { busy = true },
        onCancel = { busy = false },
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ScannerCamera(
            torchEnabled = torch,
            modifier = Modifier.fillMaxSize(),
            onBind = { capture = it },
            onPhoto = { bytes -> openEditor(bytes, scope, { busy = it }, navigator, onMessage) },
            onError = onMessage,
            onDetection = { detectedQuad = it },
        )
        detectedQuad?.let { quad ->
            Canvas(Modifier.fillMaxSize()) {
                // Camera preview uses FIT_CENTER; keep the outline in the same
                // centered 4:3 camera-content rectangle instead of stretching it
                // over the taller device viewport.
                val contentWidth = minOf(size.width, size.height * 0.75f)
                val contentHeight = minOf(size.height, size.width / 0.75f)
                val left = (size.width - contentWidth) / 2f
                val top = (size.height - contentHeight) / 2f
                val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                fun mapPoint(x: Float, y: Float) = Offset(left + x * contentWidth, top + y * contentHeight)
                val path = Path().apply {
                    val first = mapPoint(points.first().x, points.first().y)
                    moveTo(first.x, first.y)
                    points.drop(1).forEach {
                        val mapped = mapPoint(it.x, it.y)
                        lineTo(mapped.x, mapped.y)
                    }
                    close()
                }
                drawPath(path, Color(0xFF31D6A0).copy(alpha = 0.16f))
                drawPath(path, Color(0xFF31D6A0), style = Stroke(width = 3.dp.toPx()))
                points.forEach { point ->
                    val mapped = mapPoint(point.x, point.y)
                    drawCircle(Color.White, radius = 5.dp.toPx(), center = mapped)
                    drawCircle(Color(0xFF31D6A0), radius = 3.dp.toPx(), center = mapped)
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { AppGraph.session.cancel(); navigator.pop() }) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { torch = !torch }) {
                Icon(
                    if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "闪光灯",
                    tint = Color.White,
                )
            }
        }
        Text(
            text = "对齐文档后拍摄，边缘会自动识别",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 24.dp, end = 24.dp),
        )
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (!busy) importImage() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "相册", tint = Color.White)
            }
            Spacer(Modifier.width(36.dp))
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (busy || capture == null) Color.White.copy(alpha = 0.45f) else Color.White)
                    .clickable(enabled = !busy && capture != null) { capture?.invoke() },
            )
            Spacer(Modifier.width(92.dp))
        }
        BusyOverlay(busy, "正在识别边缘")
    }
}

internal fun openEditor(
    bytes: ByteArray,
    scope: kotlinx.coroutines.CoroutineScope,
    setBusy: (Boolean) -> Unit,
    navigator: Navigator,
    onMessage: (String) -> Unit,
) {
    setBusy(true)
    scope.launch {
        val prepared = withContext(Dispatchers.Default) {
            runCatching {
                val image = AppGraph.codec.decode(bytes, ScanLimits.MAX_EDGE)
                val quad = DocumentDetector.detect(image)
                image to quad
            }.getOrNull()
        }
        setBusy(false)
        if (prepared == null) {
            onMessage("这张图片无法读取")
            return@launch
        }
        AppGraph.session.source = prepared.first
        AppGraph.session.quad = prepared.second
        AppGraph.session.warped = null
        navigator.push(Screen.Crop)
    }
}
