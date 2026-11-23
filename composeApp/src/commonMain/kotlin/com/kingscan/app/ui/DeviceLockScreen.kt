package com.kingscan.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import com.kingscan.app.ui.ScanTheme as MaterialTheme
import com.kingscan.app.ui.ScanButton as Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph

@Composable
fun DeviceLockScreen() {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus(force = true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val authenticate = rememberDeviceAuthenticator { success, message ->
        busy = false
        if (success) AppGraph.unlock() else error = message
    }
    Column(Modifier.fillMaxSize().pointerInput(Unit) {
        // Give the overlay its own hit target so empty areas cannot reach the retained editor.
        awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
    }.padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("扫描件已锁定", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("通过设备系统认证后查看本机文档")
        Spacer(Modifier.height(24.dp))
        Button(onClick = { busy = true; error = ""; authenticate() }, enabled = !busy) { Text("解锁") }
        if (error.isNotEmpty()) Text(error, Modifier.padding(top = 16.dp))
    }
}
