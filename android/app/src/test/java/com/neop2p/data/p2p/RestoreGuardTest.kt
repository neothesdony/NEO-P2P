package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGuardTest {

    @Test
    fun `restore is blocked when a loadable identity exists`() {
        assertFalse(RestoreGuard.allowRestore(existingLoadable = true, force = false))
    }

    @Test
    fun `restore is allowed on a fresh device`() {
        assertTrue(RestoreGuard.allowRestore(existingLoadable = false, force = false))
    }

    @Test
    fun `restore is allowed when the stored identity is locked or invalidated`() {
        // KeyPermanentlyInvalidated / auth-gated: loadIdentityFromStorage throws
        // IdentityLockedException → not loadable → restore is the ONLY recovery.
        assertTrue(RestoreGuard.allowRestore(existingLoadable = false, force = false))
    }

    @Test
    fun `force overrides the guard`() {
        assertTrue(RestoreGuard.allowRestore(existingLoadable = true, force = true))
    }
}
