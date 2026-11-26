package com.kingscan.app.core.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingscan.app.core.imaging.CapturePreviewAnalyzer
import com.kingscan.app.core.imaging.CaptureFrame
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.imaging.ScanLimits
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

@Composable
actual fun ScannerCamera(
    torchEnabled: Boolean,
    modifier: Modifier,
    onBind: (capture: () -> Unit) -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onError: (String) -> Unit,
    onDetection: (CaptureFrame) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    if (!granted) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(24.dp),
            ) {
                Text("允许使用相机")
            }
        }
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val cameraState = remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val latestPhoto = rememberUpdatedState(onPhoto)
    val latestError = rememberUpdatedState(onError)
    val latestBind = rememberUpdatedState(onBind)
    val latestDetection = rememberUpdatedState(onDetection)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner, granted) {
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            if (disposed) return@Runnable
            val provider = runCatching { future.get() }.getOrNull() ?: run {
                latestError.value.invoke("无法打开相机")
                return@Runnable
            }
            val preview = Preview.Builder().setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                .build().also { it.surfaceProvider = previewView.surfaceProvider }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(92)
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                .build()
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            var lastAnalysisAt = 0L
            val previewAnalyzer = CapturePreviewAnalyzer()
            imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastAnalysisAt < 80L) return@setAnalyzer
                    lastAnalysisAt = now
                    val frame = runCatching { analyzePreview(proxy, now, previewAnalyzer) }.getOrNull() ?: return@setAnalyzer
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (!disposed) latestDetection.value.invoke(frame)
                    }
                } finally {
                    proxy.close()
                }
            }
            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    imageAnalysis,
                )
                cameraState.value = camera
                latestBind.value.invoke {
                    val file = File(context.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(file).build()
                    imageCapture.takePicture(
                        options,
                        executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                val bytes = runCatching { file.readBytes() }.getOrNull()
                                file.delete()
                                ContextCompat.getMainExecutor(context).execute {
                                    if (bytes != null && bytes.isNotEmpty()) latestPhoto.value.invoke(bytes)
                                    else latestError.value.invoke("没有拿到照片")
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                file.delete()
                                ContextCompat.getMainExecutor(context).execute { latestError.value.invoke("拍摄失败") }
                            }
                        },
                    )
                }
            } catch (_: Throwable) {
                latestError.value.invoke("无法打开相机")
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(torchEnabled, cameraState.value) {
        runCatching { cameraState.value?.cameraControl?.enableTorch(torchEnabled) }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun analyzePreview(proxy: ImageProxy, now: Long, analyzer: CapturePreviewAnalyzer): CaptureFrame? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val sourceWidth = proxy.width
    val sourceHeight = proxy.height
    val scale = minOf(1f, ScanLimits.DETECT_EDGE.toFloat() / maxOf(sourceWidth, sourceHeight))
    val width = (sourceWidth * scale).toInt().coerceAtLeast(2)
    val height = (sourceHeight * scale).toInt().coerceAtLeast(2)
    val bytes = plane.buffer
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val sy = (y * sourceHeight / height).coerceAtMost(sourceHeight - 1)
        for (x in 0 until width) {
            val sx = (x * sourceWidth / width).coerceAtMost(sourceWidth - 1)
            val offset = sy * plane.rowStride + sx * plane.pixelStride
            val value = (bytes.get(offset).toInt() and 0xFF).coerceIn(0, 255)
            pixels[y * width + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
    }
    var image = RgbaImage(width, height, pixels)
    repeat(((proxy.imageInfo.rotationDegrees % 360 + 360) % 360) / 90) { image = image.rotate90CW() }
    return analyzer.analyze(image, now)
}
