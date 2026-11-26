package com.kingscan.app.core.security

import kotlin.test.*

class BiometricSecurityTest {
    private fun SecurityState.authenticate(success: Boolean = true, action: () -> Unit): Boolean =
        completeAuthentication(beginAuthentication(), success, action)

    @Test
    fun coldStartAndBackgroundRequireSeparateAppAndFolderAuthentication() {
        val state = SecurityState()
        state.initialize(true, true, setOf("private"))
        assertTrue(state.appLocked)
        assertFalse(state.canAccessFolder(null))
        assertTrue(state.authenticate { state.unlockApp() })
        assertTrue(state.canAccessFolder(null))
        assertFalse(state.canAccessFolder("private"))
        assertTrue(state.authenticate { state.unlockFolder("private") })
        assertTrue(state.canAccessFolder("private"))
        state.background()
        assertTrue(state.appLocked)
        state.authenticate { state.unlockApp() }
        assertFalse(state.canAccessFolder("private"))
    }

    @Test
    fun unsupportedAndroidDoesNotActivatePersistedPolicies() {
        val state = SecurityState()
        state.initialize(false, true, setOf("private"))
        assertFalse(state.hasProtection)
        assertFalse(state.appLocked)
        assertFalse(state.isFolderLocked("private"))
        assertTrue(state.canAccessFolder("private"))
        var ran = false
        assertFalse(state.authenticate { ran = true })
        assertFalse(ran)
    }

    @Test
    fun failedSupersededAndReusedCallbacksCannotGrantAccess() {
        val state = SecurityState()
        state.initialize(true, true, emptySet())
        val old = state.beginAuthentication()
        val current = state.beginAuthentication()
        assertFalse(state.completeAuthentication(old, true) { state.unlockApp() })
        assertFalse(state.completeAuthentication(current, false) { state.unlockApp() })
        assertFalse(state.completeAuthentication(current, true) { state.unlockApp() })
        assertTrue(state.appLocked)
        val success = state.beginAuthentication()
        assertTrue(state.completeAuthentication(success, true) { state.unlockApp() })
        assertFalse(state.completeAuthentication(success, true) { state.setAppLock(false) })
        assertTrue(state.appLockEnabled)
    }

    @Test
    fun backgroundInvalidatesPendingPolicyChangeEvenWithoutAnEnabledLock() {
        val state = SecurityState()
        state.initialize(true, false, emptySet())
        val ticket = state.beginAuthentication()
        state.background()
        assertFalse(state.completeAuthentication(ticket, true) { state.setAppLock(true) })
        assertFalse(state.appLockEnabled)
    }

    @Test
    fun folderLockEnablesImmediatelyAndManualRelockInvalidatesAuthentication() {
        val state = SecurityState()
        state.initialize(true, false, emptySet())
        state.authenticate { state.setFolderLock("private", true) }
        assertTrue(state.hasProtection)
        assertFalse(state.canAccessFolder("private"))
        state.authenticate { state.unlockFolder("private") }
        assertTrue(state.canAccessFolder("private"))
        val pending = state.beginAuthentication()
        state.lockFolder("private")
        assertFalse(state.canAccessFolder("private"))
        assertFalse(state.completeAuthentication(pending, true) { state.unlockFolder("private") })
        state.authenticate { state.setFolderLock("private", false) }
        assertTrue(state.canAccessFolder("private"))
        assertFalse(state.hasProtection)
    }
}
