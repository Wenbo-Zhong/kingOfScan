@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.kingscan.app.AppGraph
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

@Composable
actual fun rememberShareLauncher(onResult: (String) -> Unit): (String, String, String) -> Unit {
    val report = rememberUpdatedState(onResult)
    return remember {
        { path, _, _ ->
            runCatching {
                val output = AppGraph.repository.prepareOutput(path)
                val presenter = topViewController() ?: return@remember
                val controller = UIActivityViewController(
                    activityItems = listOf(NSURL.fileURLWithPath(output)),
                    applicationActivities = null,
                )
                controller.popoverPresentationController?.sourceView = presenter.view
                controller.popoverPresentationController?.sourceRect = presenter.view.bounds
                presenter.presentViewController(controller, true, null)
            }.onFailure { report.value(it.message ?: "无法分享文件") }
        }
    }
}
