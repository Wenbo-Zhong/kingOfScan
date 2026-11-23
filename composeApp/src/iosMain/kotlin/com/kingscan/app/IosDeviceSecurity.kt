@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.ui

import androidx.compose.runtime.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.LocalAuthentication.*
import platform.UIKit.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberDeviceAuthenticator(onResult: (Boolean, String) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onResult)
    val contexts = remember { mutableListOf<LAContext>() }
    DisposableEffect(Unit) { onDispose { contexts.forEach { it.invalidate() }; contexts.clear() } }
    return {
        val context = LAContext()
        contexts.add(context)
        val available = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error.ptr)
        }
        if (!available) {
            contexts.remove(context)
            callback(false, "请先在系统设置中设置设备密码")
        } else context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication,
            localizedReason = "验证设备身份以查看本机扫描件") { success, error ->
            dispatch_async(dispatch_get_main_queue()) {
                contexts.remove(context)
                callback(success, if (success) "" else error?.localizedDescription ?: "认证已取消")
            }
        }
    }
}

@Composable
actual fun DevicePrivacyGuard(enabled: Boolean, onLock: () -> Unit) {
    val callback by rememberUpdatedState(onLock)
    DisposableEffect(enabled) {
        val covers = mutableListOf<UIView>()
        val center = NSNotificationCenter.defaultCenter
        val resign = center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
            if (enabled) {
                UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().forEach { window ->
                    val cover = UIView(frame = window.bounds)
                    cover.backgroundColor = UIColor.systemBackgroundColor
                    cover.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
                    window.addSubview(cover)
                    covers.add(cover)
                }
            }
        }
        val background = center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            if (enabled) callback()
        }
        val active = center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            covers.forEach { it.removeFromSuperview() }
            covers.clear()
        }
        onDispose {
            center.removeObserver(resign); center.removeObserver(active); center.removeObserver(background)
            covers.forEach { it.removeFromSuperview() }
        }
    }
}
