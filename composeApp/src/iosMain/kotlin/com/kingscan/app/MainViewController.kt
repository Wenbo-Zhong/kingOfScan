package com.kingscan.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val directories = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val base = directories.firstOrNull() as? String ?: NSTemporaryDirectory()
    AppGraph.init("$base/kingscan")
    return ComposeUIViewController { App() }
}
