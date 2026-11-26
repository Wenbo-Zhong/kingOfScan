package com.kingscan.app.core.security

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Persisted policies and foreground-only grants. Native authentication is the only grant issuer. */
class SecurityState {
    var supported by mutableStateOf(true)
        private set
    var appLockEnabled by mutableStateOf(false)
        private set
    var appLocked by mutableStateOf(false)
        private set
    var revision by mutableStateOf(0L)
        private set
    private var protectedFolders by mutableStateOf<Set<String>>(emptySet())
    private var folderGrants by mutableStateOf<Set<String>>(emptySet())
    private var epoch = 0L
    private var sequence = 0L
    private var pending: AuthenticationTicket? = null

    class AuthenticationTicket internal constructor(internal val epoch: Long, internal val sequence: Long)

    val hasProtection: Boolean get() = supported && (appLockEnabled || protectedFolders.isNotEmpty())
    fun initialize(supported: Boolean, appLock: Boolean, folders: Set<String>) {
        this.supported = supported
        appLockEnabled = appLock
        protectedFolders = folders
        background()
    }

    fun canAccessFolder(id: String?): Boolean =
        !appLocked && (!supported || id == null || id !in protectedFolders || id in folderGrants)

    fun isFolderLocked(id: String): Boolean = supported && id in protectedFolders

    fun beginAuthentication(): AuthenticationTicket =
        AuthenticationTicket(epoch, ++sequence).also { pending = it }

    /** One use, bound to both the request and the current foreground session. */
    fun completeAuthentication(ticket: AuthenticationTicket, success: Boolean, action: () -> Unit): Boolean {
        if (pending !== ticket || ticket.epoch != epoch) return false
        pending = null
        if (!success || !supported) return false
        action()
        return true
    }

    internal fun unlockApp() { appLocked = false; revision++ }
    internal fun setAppLock(enabled: Boolean) {
        appLockEnabled = enabled
        appLocked = false
        revision++
    }
    internal fun unlockFolder(id: String) {
        check(!appLocked) { "请先解锁应用" }
        folderGrants = folderGrants + id
        revision++
    }
    internal fun setFolderLock(id: String, enabled: Boolean) {
        protectedFolders = if (enabled) protectedFolders + id else protectedFolders - id
        // Enabling a folder lock immediately protects it; entering it requires another authentication.
        folderGrants = folderGrants - id
        revision++
    }
    internal fun removeFolder(id: String) {
        protectedFolders = protectedFolders - id
        folderGrants = folderGrants - id
        revision++
    }
    fun lockFolder(id: String) {
        folderGrants = folderGrants - id
        epoch++
        pending = null
        revision++
    }
    fun background() {
        epoch++
        pending = null
        folderGrants = emptySet()
        appLocked = supported && appLockEnabled
        revision++
    }
}

class DocumentLockedException : IllegalStateException("请先通过系统生物识别解锁")
