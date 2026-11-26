package com.kingscan.app.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kingscan.app.core.imaging.CaptureFrame
import com.kingscan.app.core.document.ImportFile

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
expect fun rememberShareLauncher(onResult: (String) -> Unit = {}): (String, String, String) -> Unit

@Composable
expect fun rememberGallerySaver(onResult: (String) -> Unit): (List<String>) -> Unit

@Composable
expect fun rememberPrintLauncher(onResult: (String) -> Unit): (String, String) -> Unit

/** Uses system biometrics only, without a device-passcode fallback. */
@Composable
expect fun rememberDeviceAuthenticator(): DeviceAuthenticator

@Composable
expect fun DevicePrivacyGuard(enabled: Boolean, onLock: () -> Unit)
