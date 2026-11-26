@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import platform.Foundation.NSURL
import platform.Photos.*
import platform.UIKit.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberGallerySaver(onResult: (String) -> Unit): (List<String>) -> Unit {
    val report = rememberUpdatedState(onResult)
    return { paths ->
        if (paths.isNotEmpty()) {
            val snapshot = paths.toList()
            fun save() {
                val access = runCatching { snapshot.forEach(com.kingscan.app.AppGraph.repository::requirePathAccess) }
                if (access.isFailure) {
                    report.value(access.exceptionOrNull()?.message ?: "请先解锁文档")
                    return
                }
                PHPhotoLibrary.sharedPhotoLibrary().performChanges({
                    snapshot.forEach { path ->
                        PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(NSURL.fileURLWithPath(path))
                    }
                }, { success, error ->
                    dispatch_async(dispatch_get_main_queue()) {
                        report.value(if (success) "已保存 ${snapshot.size} 张到相册" else error?.localizedDescription ?: "保存相册失败")
                    }
                })
            }
            val status = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
            when (status) {
                PHAuthorizationStatusAuthorized, PHAuthorizationStatusLimited -> save()
                PHAuthorizationStatusNotDetermined -> PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (granted == PHAuthorizationStatusAuthorized || granted == PHAuthorizationStatusLimited) save()
                        else report.value("需要相册写入权限才能保存")
                    }
                }
                else -> report.value("请在系统设置中允许保存到相册")
            }
        }
    }
}

@Composable
actual fun rememberPrintLauncher(onResult: (String) -> Unit): (String, String) -> Unit {
    val report = rememberUpdatedState(onResult)
    return { path, title ->
        runCatching {
            val url = NSURL.fileURLWithPath(com.kingscan.app.AppGraph.repository.prepareOutput(path))
            if (!UIPrintInteractionController.canPrintURL(url)) report.value("这个文件无法打印")
            else {
                val controller = UIPrintInteractionController.sharedPrintController()
                controller.printInfo = UIPrintInfo.printInfo().apply {
                    jobName = title
                }
                controller.printingItem = url
                val completion: (UIPrintInteractionController?, Boolean, platform.Foundation.NSError?) -> Unit = { _, completed, error ->
                    if (error != null) report.value(error.localizedDescription)
                    else if (completed) report.value("已提交系统打印")
                }
                val presenter = topViewController()
                if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad && presenter != null)
                    controller.presentFromRect(presenter.view.bounds, presenter.view, true, completion)
                else controller.presentAnimated(true, completion)
            }
        }.onFailure { report.value(it.message ?: "无法打开系统打印") }
    }
}
