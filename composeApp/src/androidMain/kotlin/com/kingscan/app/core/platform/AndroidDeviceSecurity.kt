package com.kingscan.app.core.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.fingerprint.FingerprintManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

fun supportsSystemBiometrics(context: Context): Boolean = Build.VERSION.SDK_INT >= 28 &&
    listOf(PackageManager.FEATURE_FINGERPRINT, "android.hardware.biometrics.face", "android.hardware.biometrics.iris")
        .any { context.packageManager.hasSystemFeature(it) }

private class AndroidDeviceAuthenticator(private val context: Context) : DeviceAuthenticator() {
    private var sequence = 0L
    private var signal: CancellationSignal? = null
    private var callback: ((Boolean, String) -> Unit)? = null

    override fun refresh() {
        availability = when {
            !supportsSystemBiometrics(context) -> BiometricAvailability.Unsupported
            Build.VERSION.SDK_INT >= 29 -> {
                val manager = context.getSystemService(BiometricManager::class.java)
                val result = if (Build.VERSION.SDK_INT >= 30)
                    manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                else @Suppress("DEPRECATION") manager?.canAuthenticate()
                when (result) {
                    BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Ready
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
                    else -> BiometricAvailability.Unavailable
                }
            }
            else -> {
                // API 28 has BiometricPrompt but no BiometricManager. Face/iris enrollment is
                // confirmed by the prompt itself before any policy or grant can be issued.
                @Suppress("DEPRECATION")
                val fingerprint = context.getSystemService(FingerprintManager::class.java)
                if (!context.packageManager.hasSystemFeature("android.hardware.biometrics.face") &&
                    !context.packageManager.hasSystemFeature("android.hardware.biometrics.iris") &&
                    fingerprint?.hasEnrolledFingerprints() != true) BiometricAvailability.NotEnrolled
                else BiometricAvailability.Ready
            }
        }
    }

    override fun cancel() {
        sequence++
        val previous = callback
        callback = null
        val previousSignal = signal
        signal = null
        busy = false
        previousSignal?.cancel()
        previous?.invoke(false, "认证已取消")
    }

    override fun authenticate(reason: String, onResult: (Boolean, String) -> Unit) {
        cancel()
        refresh()
        if (availability != BiometricAvailability.Ready || Build.VERSION.SDK_INT < 28) {
            onResult(false, availability.message)
            return
        }
        val activity = context.activity()
        if (activity == null) { onResult(false, "无法启动系统认证"); return }
        val request = ++sequence
        val cancellation = CancellationSignal()
        signal = cancellation
        callback = onResult
        busy = true
        fun finish(success: Boolean, message: String) {
            if (request != sequence) return
            val result = callback
            callback = null
            signal = null
            busy = false
            result?.invoke(success, message)
        }
        runCatching {
            val builder = BiometricPrompt.Builder(activity).setTitle("离线扫描王")
                .setSubtitle(reason)
                .setNegativeButton("取消", activity.mainExecutor) { _, _ -> finish(false, "认证已取消") }
            if (Build.VERSION.SDK_INT >= 30)
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            if (Build.VERSION.SDK_INT >= 29) builder.setConfirmationRequired(true)
            builder.build().authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(true, "")
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(false, errString.toString())
            })
        }.onFailure { finish(false, it.message ?: "无法启动系统认证") }
    }
}

@Composable
actual fun rememberDeviceAuthenticator(): DeviceAuthenticator {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val authenticator = remember(context) { AndroidDeviceAuthenticator(context).apply { refresh() } }
    DisposableEffect(authenticator, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) authenticator.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); authenticator.cancel() }
    }
    return authenticator
}

@Composable
actual fun DevicePrivacyGuard(enabled: Boolean, onLock: () -> Unit) {
    val activity = LocalContext.current.activity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callback by rememberUpdatedState(onLock)
    DisposableEffect(enabled, lifecycle) {
        if (enabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) callback()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
