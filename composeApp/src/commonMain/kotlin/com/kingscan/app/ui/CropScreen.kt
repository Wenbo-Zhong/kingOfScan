package com.kingscan.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.imaging.DocumentDetector
import com.kingscan.app.imaging.PerspectiveWarp
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.model.PointF
import com.kingscan.app.model.Quad
import com.kingscan.app.model.Screen
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CropScreen(navigator: Navigator) {
    val initial = AppGraph.session.source
    if (initial == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { navigator.pop() }) { Text("返回重拍") }
        }
        return
    }
    var image by remember { mutableStateOf(initial) }
    var quad by remember { mutableStateOf(AppGraph.session.quad) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScanTopBar(
            title = "裁切",
            onBack = { if (!busy) navigator.pop() },
            contentColor = Color.White,
        ) {
            TextButton(onClick = { quad = Quad.full() }, enabled = !busy) { Text("全图", color = Color.White) }
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        quad = withContext(Dispatchers.Default) { DocumentDetector.detect(image) }
                        busy = false
                    }
                },
                enabled = !busy,
            ) { Text("自动", color = Color.White) }
            TextButton(
                onClick = {
                    image = image.rotate90CW()
                    quad = quad.rotate90CW()
                    AppGraph.session.source = image
                },
                enabled = !busy,
            ) {
                androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "旋转", tint = Color.White)
            }
        }
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val imageRatio = image.width.toFloat() / image.height.toFloat()
            val boxRatio = maxWidth / maxHeight
            val drawW = if (boxRatio > imageRatio) maxHeight * imageRatio else maxWidth
            val drawH = if (boxRatio > imageRatio) maxHeight else maxWidth / imageRatio
            CropCanvas(
                image = image,
                quad = quad,
                onQuad = { quad = it },
                modifier = Modifier.size(drawW, drawH),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("拖动四角贴合纸张边缘，在选区内拖动可整体移动", color = Color.White.copy(alpha = 0.8f))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val warped = withContext(Dispatchers.Default) { PerspectiveWarp.warp(image, quad) }
                        AppGraph.session.source = image
                        AppGraph.session.quad = quad
                        AppGraph.session.warped = warped
                        busy = false
                        navigator.push(Screen.Filter)
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("下一步") }
        }
        }
        BusyOverlay(busy, "正在校正透视")
    }
}

@Composable
private fun CropCanvas(
    image: RgbaImage,
    quad: Quad,
    onQuad: (Quad) -> Unit,
    modifier: Modifier,
) {
    val latestQuad = rememberUpdatedState(quad)
    Box(
        modifier.pointerInput(image) {
            var mode: DragMode = DragMode.None
            detectDragGestures(
                onDragStart = { offset ->
                    mode = hit(offset, latestQuad.value, size.width.toFloat(), size.height.toFloat(), 32.dp.toPx())
                },
                onDragEnd = { mode = DragMode.None },
                onDragCancel = { mode = DragMode.None },
                onDrag = { change, drag ->
                    val current = latestQuad.value
                    val dx = drag.x / size.width
                    val dy = drag.y / size.height
                    val next = when (val dragging = mode) {
                        DragMode.None -> current
                        DragMode.Move -> current.translate(dx, dy)
                        is DragMode.Corner -> {
                            val point = current.corner(dragging.index)
                            current.withCorner(dragging.index, PointF(point.x + dx, point.y + dy))
                        }
                    }
                    if (next != current) {
                        change.consume()
                        onQuad(next)
                    }
                },
            )
        },
    ) {
        RgbaImageView(image, Modifier.fillMaxSize(), contentDescription = "待裁切文档")
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            fun offset(point: PointF) = Offset(point.x * width, point.y * height)
            val topLeft = offset(quad.topLeft)
            val topRight = offset(quad.topRight)
            val bottomRight = offset(quad.bottomRight)
            val bottomLeft = offset(quad.bottomLeft)
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, width, height))
                moveTo(topLeft.x, topLeft.y)
                lineTo(topRight.x, topRight.y)
                lineTo(bottomRight.x, bottomRight.y)
                lineTo(bottomLeft.x, bottomLeft.y)
                close()
            }
            drawPath(shade, Color.Black.copy(alpha = 0.48f))
            val stroke = Stroke(width = 2.dp.toPx())
            drawLine(Seal, topLeft, topRight, strokeWidth = stroke.width)
            drawLine(Seal, topRight, bottomRight, strokeWidth = stroke.width)
            drawLine(Seal, bottomRight, bottomLeft, strokeWidth = stroke.width)
            drawLine(Seal, bottomLeft, topLeft, strokeWidth = stroke.width)
            listOf(topLeft, topRight, bottomRight, bottomLeft).forEach { center ->
                drawCircle(Color.White, radius = 11.dp.toPx(), center = center)
                drawCircle(Seal, radius = 6.dp.toPx(), center = center)
            }
        }
    }
}

private sealed interface DragMode {
    data object None : DragMode
    data object Move : DragMode
    data class Corner(val index: Int) : DragMode
}

private fun hit(offset: Offset, quad: Quad, width: Float, height: Float, radius: Float): DragMode {
    val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
    corners.forEachIndexed { index, point ->
        val dx = offset.x - point.x * width
        val dy = offset.y - point.y * height
        if (sqrt(dx * dx + dy * dy) <= radius) return DragMode.Corner(index)
    }
    val nx = offset.x / width
    val ny = offset.y / height
    return if (pointInQuad(nx, ny, quad)) DragMode.Move else DragMode.None
}

private fun pointInQuad(x: Float, y: Float, quad: Quad): Boolean {
    val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
    var inside = false
    var previous = points.lastIndex
    for (index in points.indices) {
        val current = points[index]
        val other = points[previous]
        val crosses = (current.y > y) != (other.y > y) &&
            x < (other.x - current.x) * (y - current.y) / ((other.y - current.y) + 0.00001f) + current.x
        if (crosses) inside = !inside
        previous = index
    }
    return inside
}

private fun Quad.corner(index: Int): PointF = when (index) {
    0 -> topLeft
    1 -> topRight
    2 -> bottomRight
    else -> bottomLeft
}
