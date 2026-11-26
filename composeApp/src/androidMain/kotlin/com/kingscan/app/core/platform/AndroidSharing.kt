package com.kingscan.app.core.platform

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.kingscan.app.AppGraph
import androidx.core.content.FileProvider
import java.io.File

@Composable
actual fun rememberShareLauncher(onResult: (String) -> Unit): (String, String, String) -> Unit {
    val context = LocalContext.current
    val report = rememberUpdatedState(onResult)
    return remember(context) {
        { path, mime, title ->
            runCatching {
                val output = AppGraph.repository.prepareOutput(path)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(output))
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, title))
            }.onFailure { report.value(it.message ?: "无法分享文件") }
        }
    }
}
