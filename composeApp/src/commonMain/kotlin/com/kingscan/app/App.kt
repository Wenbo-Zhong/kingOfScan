package com.kingscan.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.kingscan.app.core.ui.ScanTheme as MaterialTheme
import androidx.compose.material3.Scaffold
import com.kingscan.app.core.ui.ScanMessageHost as SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import com.kingscan.app.navigation.Screen
import com.kingscan.app.feature.scan.CaptureScreen
import com.kingscan.app.feature.editor.CropScreen
import com.kingscan.app.feature.editor.DetailScreen
import com.kingscan.app.feature.editor.FilterScreen
import com.kingscan.app.core.ui.KingScanTheme
import com.kingscan.app.feature.library.LibraryScreen
import com.kingscan.app.navigation.Navigator
import com.kingscan.app.core.platform.PlatformBackHandler
import com.kingscan.app.feature.scan.PdfUnlockScreen
import com.kingscan.app.feature.ocr.OcrScreen
import com.kingscan.app.feature.settings.SettingsScreen
import com.kingscan.app.feature.export.ExportScreen
import com.kingscan.app.feature.editor.AnnotationScreen
import com.kingscan.app.feature.editor.PageManagementScreen
import com.kingscan.app.feature.scan.BookScreen
import com.kingscan.app.feature.editor.CompositionScreen
import com.kingscan.app.feature.editor.RetouchScreen
import com.kingscan.app.core.platform.rememberGallerySaver
import com.kingscan.app.feature.scan.importFiles
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.feature.codes.CodeScreen
import com.kingscan.app.feature.security.DeviceLockScreen
import com.kingscan.app.core.platform.DevicePrivacyGuard
import com.kingscan.app.core.platform.LocalDeviceAuthenticator
import com.kingscan.app.core.platform.LocalContentLocked
import com.kingscan.app.core.platform.rememberDeviceAuthenticator
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@Composable
fun App() {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val navigator = remember { Navigator(scope) }
    var externalBusy by remember { mutableStateOf(false) }
    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }
    val gallerySaver = rememberGallerySaver(::message)
    val currentGallerySaver by rememberUpdatedState(gallerySaver)
    val authenticator = rememberDeviceAuthenticator()
    DevicePrivacyGuard(AppGraph.security.hasProtection) {
        AppGraph.lock()
        authenticator.cancel()
        if (AppGraph.security.hasProtection) AppGraph.session.interruptProcessing()
    }
    val routeDocumentId = when (val screen = navigator.current) {
        is Screen.Detail -> screen.documentId
        is Screen.Ocr -> screen.documentId
        is Screen.Export -> screen.documentId
        is Screen.Annotate -> screen.documentId
        is Screen.Retouch -> screen.documentId
        is Screen.Pages -> screen.documentId
        is Screen.ComposePages -> screen.documentId
        Screen.Capture, Screen.Crop, Screen.Filter, Screen.Book -> AppGraph.session.documentId
        else -> null
    }
    // Observe grant changes even when the route and retained editor have not changed.
    val securityRevision = AppGraph.security.revision
    val blockedFolder = remember(routeDocumentId, securityRevision) {
        AppGraph.repository.blockedFolderForDocument(routeDocumentId)
    }
    val contentLocked = AppGraph.locked || blockedFolder != null
    LaunchedEffect(Unit) {
        AppGraph.savedImages.receiveAsFlow().collect {
            snapshotFlow { AppGraph.locked }.first { locked -> !locked }
            currentGallerySaver(it)
        }
    }
    LaunchedEffect(Unit) {
        AppGraph.externalImports.receiveAsFlow().collect { imported ->
            snapshotFlow { AppGraph.locked }.first { locked -> !locked }
            imported.error?.let(::message)
            if (imported.files.isNotEmpty()) {
                AppGraph.session.beginNew()
                navigator.showLibrary()
                importFiles(imported.files, { externalBusy = it }, navigator, ::message)
            }
        }
    }
    PlatformBackHandler(enabled = navigator.current != Screen.Library || contentLocked) {
        if (AppGraph.locked) return@PlatformBackHandler
        if (blockedFolder != null) {
            scope.launch { AppGraph.session.cancel(); navigator.showLibrary() }
            return@PlatformBackHandler
        }
        if (!AppGraph.session.transitioning) {
            scope.launch {
                when (navigator.current) {
                    Screen.Capture, is Screen.Detail, is Screen.PdfUnlock -> AppGraph.session.cancel()
                    else -> if (AppGraph.session.processing) AppGraph.session.cancel()
                }
                navigator.pop()
            }
        }
    }
    CompositionLocalProvider(LocalDeviceAuthenticator provides authenticator) {
        KingScanTheme {
            Scaffold(
                snackbarHost = { if (!contentLocked) SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Preserve unsaved editor state while hiding locked content and its popups.
                    CompositionLocalProvider(LocalContentLocked provides contentLocked) {
                        Box(Modifier.fillMaxSize().then(if (contentLocked)
                            Modifier.alpha(0f).semantics { hideFromAccessibility() } else Modifier)) {
                            when (val screen = navigator.current) {
                                Screen.Library -> LibraryScreen(navigator, ::message)
                                Screen.Capture -> CaptureScreen(navigator, ::message)
                                Screen.Crop -> CropScreen(navigator)
                                Screen.Filter -> FilterScreen(navigator, ::message)
                                Screen.Book -> BookScreen(navigator, ::message)
                                is Screen.Detail -> DetailScreen(screen.documentId, navigator, ::message)
                                is Screen.PdfUnlock -> PdfUnlockScreen(screen.file, navigator, ::message)
                                is Screen.Ocr -> OcrScreen(screen.documentId, screen.pageId, navigator, ::message)
                                Screen.Settings -> SettingsScreen(navigator, ::message)
                                Screen.Codes -> CodeScreen(navigator, ::message)
                                is Screen.Export -> ExportScreen(screen.documentId, navigator, ::message)
                                is Screen.Annotate -> AnnotationScreen(screen.documentId, screen.pageId, navigator, ::message)
                                is Screen.Retouch -> RetouchScreen(screen.documentId, screen.pageId, navigator, ::message)
                                is Screen.Pages -> PageManagementScreen(screen.documentId, navigator, ::message)
                                is Screen.ComposePages -> CompositionScreen(screen, navigator, ::message)
                            }
                            BusyOverlay(externalBusy, "正在导入外部文件")
                            BusyOverlay(AppGraph.session.transitioning, "正在结束上一项处理")
                        }
                    }
                    if (contentLocked) DeviceLockScreen(if (AppGraph.locked) null else blockedFolder) {
                        scope.launch { AppGraph.session.cancel(); navigator.showLibrary() }
                    }
                }
            }
        }
    }
}
