package com.kingscan.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

@Composable
expect fun ScannerCamera(
    torchEnabled: Boolean,
    modifier: Modifier,
    onBind: (capture: () -> Unit) -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onError: (String) -> Unit,
)

@Composable
expect fun rememberImageImporter(
    onImage: (ByteArray) -> Unit,
    onError: (String) -> Unit = {},
    onStart: () -> Unit = {},
    onCancel: () -> Unit = {},
): () -> Unit

@Composable
expect fun rememberShareLauncher(): (String, String, String) -> Unit
