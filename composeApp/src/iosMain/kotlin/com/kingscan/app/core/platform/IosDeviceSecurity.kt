@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.kingscan.app.core.platform

import androidx.compose.runtime.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.LocalAuthentication.*
import platform.UIKit.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private class IosDeviceAuthenticator : DeviceAuthenticator() {
    private var sequence = 0L
    private var active: LAContext? = null
    private var callback: ((Boolean, String) -> Unit)? = null

    override fun refresh() {
        val context = LAContext()
        availability = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            if (context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error.ptr))
                BiometricAvailability.Ready
            else when (error.value?.code) {
                LAErrorBiometryNotEnrolled -> BiometricAvailability.NotEnrolled
                // Unavailable also covers revoked Face ID permission; never drop a saved lock.
                else -> BiometricAvailability.Unavailable
            }
        }
        context.invalidate()
    }

    override fun cancel() {
        sequence++
        val previous = callback
        callback = null
        active?.invalidate()
        active = null
        busy = false
        previous?.invoke(false, "认证已取消")
    }

    override fun authenticate(reason: String, onResult: (Boolean, String) -> Unit) {
        cancel()
        refresh()
        if (availability != BiometricAvailability.Ready) { onResult(false, availability.message); return }
        val request = ++sequence
        val context = LAContext().apply {
            localizedFallbackTitle = ""
            touchIDAuthenticationAllowableReuseDuration = 0.0
        }
        active = context
        callback = onResult
        busy = true
        context.evaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            localizedReason = reason) { success, error ->
            dispatch_async(dispatch_get_main_queue()) {
                if (request == sequence) {
                    val result = callback
                    callback = null
                    active = null
                    busy = false
                    context.invalidate()
                    result?.invoke(success, if (success) "" else error?.localizedDescription ?: "认证已取消")
                }
            }
        }
    }
}

@Composable
actual fun rememberDeviceAuthenticator(): DeviceAuthenticator {
    val authenticator = remember { IosDeviceAuthenticator().apply { refresh() } }
    DisposableEffect(authenticator) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            authenticator.refresh()
        }
        onDispose { center.removeObserver(observer); authenticator.cancel() }
    }
    return authenticator
}

@Composable
actual fun DevicePrivacyGuard(enabled: Boolean, onLock: () -> Unit) {
    val callback by rememberUpdatedState(onLock)
    val protect by rememberUpdatedState(enabled)
    val covers = remember { mutableListOf<UIView>() }
    var foreground by remember { mutableStateOf(true) }
    // Remove the native snapshot cover only after the locked Compose frame has been applied.
    SideEffect {
        if (foreground) {
            covers.forEach { it.removeFromSuperview() }
            covers.clear()
        }
    }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val resign = center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
            foreground = false
            if (protect) {
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
            callback()
        }
        val active = center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            foreground = true
        }
        onDispose {
            center.removeObserver(resign); center.removeObserver(active); center.removeObserver(background)
            covers.forEach { it.removeFromSuperview() }
        }
    }
}
