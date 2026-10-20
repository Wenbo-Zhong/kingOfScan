package com.kingscan.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun ScannerCamera(
    torchEnabled: Boolean,
    modifier: Modifier,
    onBind: (capture: () -> Unit) -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner, granted) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = runCatching { future.get() }.getOrNull() ?: run {
                latestError.value.invoke("无法打开相机")
                return@Runnable
            }
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(92)
                .build()
            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
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
                                if (bytes != null && bytes.isNotEmpty()) latestPhoto.value.invoke(bytes)
                                else latestError.value.invoke("没有拿到照片")
                            }

                            override fun onError(exception: ImageCaptureException) {
                                file.delete()
                                latestError.value.invoke("拍摄失败")
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
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }

    LaunchedEffect(torchEnabled, cameraState.value) {
        runCatching { cameraState.value?.cameraControl?.enableTorch(torchEnabled) }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
actual fun rememberImageImporter(
    onImage: (ByteArray) -> Unit,
    onError: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onImage)
    val latestError = rememberUpdatedState(onError)
    val latestStart = rememberUpdatedState(onStart)
    val latestCancel = rememberUpdatedState(onCancel)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            latestCancel.value.invoke()
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (bytes != null && bytes.isNotEmpty()) latest.value.invoke(bytes)
                else latestError.value.invoke("无法读取所选图片")
            }
        }
    }
    return {
        latestStart.value.invoke()
        runCatching {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure { latestError.value.invoke("无法打开相册") }
    }
}

@Composable
actual fun rememberShareLauncher(): (String, String, String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { path, mime, title ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, title))
        }
    }
}
