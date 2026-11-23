package com.kingscan.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

@Composable
actual fun rememberDeviceAuthenticator(onResult: (Boolean, String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    val signal = remember { CancellationSignal() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        callback(it.resultCode == Activity.RESULT_OK, if (it.resultCode == Activity.RESULT_OK) "" else "认证已取消")
    }
    DisposableEffect(Unit) { onDispose { signal.cancel() } }
    return {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) callback(false, "请先在系统设置中设置设备密码")
        else if (Build.VERSION.SDK_INT >= 30) {
            val activity = context.activity()
            if (activity == null) callback(false, "无法启动系统认证")
            else BiometricPrompt.Builder(activity).setTitle("解锁离线扫描王")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build().authenticate(signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = callback(true, "")
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = callback(false, errString.toString())
                })
        } else {
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent("解锁离线扫描王", "验证设备密码以查看本机扫描件")
            if (intent == null) callback(false, "无法启动系统认证") else launcher.launch(intent)
        }
    }
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
            if (enabled && event == Lifecycle.Event.ON_STOP) callback()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
