package com.kingscan.app.feature.security

import com.kingscan.app.core.ui.ScanTextButton
import com.kingscan.app.core.platform.LocalDeviceAuthenticator
import com.kingscan.app.core.platform.rememberSecureAuthentication

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanTheme as MaterialTheme
import com.kingscan.app.core.ui.ScanButton as Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph

@Composable
fun DeviceLockScreen(folderId: String? = null, onCloseFolder: () -> Unit = {}) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus(force = true) }
    var error by remember(folderId) { mutableStateOf("") }
    val authenticator = LocalDeviceAuthenticator.current
    val authenticate = rememberSecureAuthentication { error = it }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
        // Give the overlay its own hit target so empty areas cannot reach the retained editor.
        awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
    }.padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (folderId == null) "应用已锁定" else "文件夹已锁定", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("通过系统面容或指纹认证后查看文档")
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            error = ""
            authenticate(if (folderId == null) "解锁应用以查看本机文档" else "解锁文件夹以查看文档") {
                if (folderId == null) AppGraph.unlock() else AppGraph.unlockFolder(folderId)
            }
        }, enabled = !authenticator.busy) { Text("解锁") }
        if (folderId != null) ScanTextButton(onClick = onCloseFolder) { Text("返回文稿库") }
        if (error.isNotEmpty()) Text(error, Modifier.padding(top = 16.dp))
    }
}
