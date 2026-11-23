package com.kingscan.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kingscan.app.imaging.CaptureFrame
import com.kingscan.app.model.ImportFile

@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

@Composable
expect fun ScannerCamera(
    torchEnabled: Boolean,
    modifier: Modifier,
    onBind: (capture: () -> Unit) -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onError: (String) -> Unit,
    onDetection: (CaptureFrame) -> Unit = {},
)

@Composable
expect fun rememberImagesImporter(
    onImages: (List<ImportFile>) -> Unit,
    onError: (String) -> Unit = {},
    onStart: () -> Unit = {},
    onCancel: () -> Unit = {},
): () -> Unit

@Composable
expect fun rememberFileImporter(
    onFiles: (List<ImportFile>) -> Unit,
    onError: (String) -> Unit = {},
    onStart: () -> Unit = {},
    onCancel: () -> Unit = {},
): () -> Unit

@Composable
expect fun rememberShareLauncher(): (String, String, String) -> Unit

@Composable
expect fun rememberGallerySaver(onResult: (String) -> Unit): (List<String>) -> Unit

@Composable
expect fun rememberPrintLauncher(onResult: (String) -> Unit): (String, String) -> Unit

/** Result is true only after the operating system confirms device-owner authentication. */
@Composable
expect fun rememberDeviceAuthenticator(onResult: (Boolean, String) -> Unit): () -> Unit

@Composable
expect fun DevicePrivacyGuard(enabled: Boolean, onLock: () -> Unit)
