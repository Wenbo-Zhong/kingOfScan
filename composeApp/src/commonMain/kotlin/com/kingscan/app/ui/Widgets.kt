package com.kingscan.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import com.kingscan.app.ui.ScanProgressIndicator as CircularProgressIndicator
import com.kingscan.app.ui.ScanTheme as MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.imaging.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ScanLinearProgress(progress: () -> Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, trackColor: Color = color.copy(alpha = .2f)) {
    val value = progress().coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(trackColor)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }) {
        Box(Modifier.fillMaxWidth(value).height(4.dp).background(color))
    }
}

@Composable
fun RgbaImageView(
    image: RgbaImage,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var bitmap by remember(image) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image) {
        bitmap = withContext(Dispatchers.Default) {
            runCatching { image.toComposeImageBitmap() }.getOrNull()
        }
    }
    val current = bitmap
    if (current == null) {
        Box(modifier.background(Color.Black))
    } else {
        Image(
            bitmap = current,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
fun JpegThumb(
    path: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = AppGraph.repository.readBytes(path)
                val image = AppGraph.codec.decode(bytes, 480)
                image.toComposeImageBitmap()
            }.getOrNull()
        }
    }
    val current = bitmap
    if (current == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        Image(
            bitmap = current,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
fun BusyOverlay(visible: Boolean, label: String) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(animationSpec = tween(durationMillis = 100)),
        exit = fadeOut(animationSpec = tween(durationMillis = 180)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(label, color = Color.White)
            }
        }
    }
}

@Composable
fun PageImage(
    path: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = AppGraph.repository.readBytes(path)
                AppGraph.codec.decode(bytes, 1600).toComposeImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current == null) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        } else {
            Image(
                bitmap = current,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
