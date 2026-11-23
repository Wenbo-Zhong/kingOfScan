package com.kingscan.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.kingscan.app.ui.ScanTheme as MaterialTheme
import androidx.compose.material3.Scaffold
import com.kingscan.app.ui.ScanMessageHost as SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import com.kingscan.app.model.Screen
import com.kingscan.app.ui.CaptureScreen
import com.kingscan.app.ui.CropScreen
import com.kingscan.app.ui.DetailScreen
import com.kingscan.app.ui.FilterScreen
import com.kingscan.app.ui.KingScanTheme
import com.kingscan.app.ui.LibraryScreen
import com.kingscan.app.ui.Navigator
import com.kingscan.app.ui.PlatformBackHandler
import com.kingscan.app.ui.PdfUnlockScreen
import com.kingscan.app.ui.OcrScreen
import com.kingscan.app.ui.SettingsScreen
import com.kingscan.app.ui.ExportScreen
import com.kingscan.app.ui.AnnotationScreen
import com.kingscan.app.ui.PageManagementScreen
import com.kingscan.app.ui.BookScreen
import com.kingscan.app.ui.CompositionScreen
import com.kingscan.app.ui.RetouchScreen
import com.kingscan.app.ui.rememberGallerySaver
import com.kingscan.app.ui.importFiles
import com.kingscan.app.ui.BusyOverlay
import com.kingscan.app.ui.CodeScreen
import com.kingscan.app.ui.DeviceLockScreen
import com.kingscan.app.ui.DevicePrivacyGuard
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
    DevicePrivacyGuard(AppGraph.deviceLockEnabled, AppGraph::lock)
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
    PlatformBackHandler(enabled = !AppGraph.locked && navigator.current != Screen.Library) {
        when (navigator.current) {
            Screen.Capture, is Screen.Detail -> AppGraph.session.cancel()
            is Screen.PdfUnlock -> {
                AppGraph.repository.finishImport((navigator.current as Screen.PdfUnlock).file)
                AppGraph.session.cancel()
            }
            else -> Unit
        }
        navigator.pop()
    }
    KingScanTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                // Preserve unsaved editor state, while removing locked content from rendering and accessibility.
                Box(Modifier.fillMaxSize().then(if (AppGraph.locked)
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
                }
                if (AppGraph.locked) DeviceLockScreen()
            }
        }
    }
}
