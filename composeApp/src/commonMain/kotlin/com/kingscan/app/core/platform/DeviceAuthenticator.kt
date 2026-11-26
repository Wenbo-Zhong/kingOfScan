package com.kingscan.app.core.platform

import androidx.compose.runtime.*
import com.kingscan.app.AppGraph

enum class BiometricAvailability(val message: String) {
    Ready(""),
    Unsupported("此设备不支持系统生物识别，无法启用应用锁或文件夹锁"),
    NotEnrolled("请先在系统设置中录入面容或指纹"),
    Unavailable("系统生物识别暂不可用，请稍后重试"),
}

abstract class DeviceAuthenticator {
    var availability by mutableStateOf(BiometricAvailability.Unavailable)
        protected set
    var busy by mutableStateOf(false)
        protected set
    abstract fun refresh()
    abstract fun authenticate(reason: String, onResult: (Boolean, String) -> Unit)
    abstract fun cancel()
}

val LocalDeviceAuthenticator = staticCompositionLocalOf<DeviceAuthenticator> { error("缺少系统认证器") }
val LocalContentLocked = staticCompositionLocalOf { false }

/** Keeps success attached to the initiating action, never to a newly recomposed callback. */
@Composable
fun rememberSecureAuthentication(onMessage: (String) -> Unit): (String, () -> Unit) -> Unit {
    val authenticator = LocalDeviceAuthenticator.current
    DisposableEffect(authenticator) { onDispose { authenticator.cancel() } }
    return { reason, action ->
        authenticator.refresh()
        if (!AppGraph.security.supported || authenticator.availability != BiometricAvailability.Ready) {
            onMessage(authenticator.availability.message.ifEmpty { BiometricAvailability.Unsupported.message })
        } else {
            authenticator.cancel()
            val ticket = AppGraph.security.beginAuthentication()
            authenticator.authenticate(reason) { success, error ->
                runCatching {
                    AppGraph.security.completeAuthentication(ticket, success, action)
                }.onFailure { onMessage(it.message ?: "操作失败") }
                if (!success && error.isNotEmpty()) onMessage(error)
            }
        }
    }
}
