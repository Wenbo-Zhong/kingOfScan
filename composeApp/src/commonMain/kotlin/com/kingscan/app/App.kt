package com.kingscan.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.kingscan.app.model.Screen
import com.kingscan.app.ui.CaptureScreen
import com.kingscan.app.ui.CropScreen
import com.kingscan.app.ui.DetailScreen
import com.kingscan.app.ui.FilterScreen
import com.kingscan.app.ui.KingScanTheme
import com.kingscan.app.ui.LibraryScreen
import com.kingscan.app.ui.Navigator
import com.kingscan.app.ui.PlatformBackHandler
import kotlinx.coroutines.launch

@Composable
fun App() {
    val navigator = remember { Navigator() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }
    PlatformBackHandler(enabled = navigator.current != Screen.Library) {
        when (navigator.current) {
            Screen.Capture, is Screen.Detail -> AppGraph.session.cancel()
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
                when (val screen = navigator.current) {
                    Screen.Library -> LibraryScreen(navigator, ::message)
                    Screen.Capture -> CaptureScreen(navigator, ::message)
                    Screen.Crop -> CropScreen(navigator)
                    Screen.Filter -> FilterScreen(navigator, ::message)
                    is Screen.Detail -> DetailScreen(screen.documentId, navigator, ::message)
                }
            }
        }
    }
}
