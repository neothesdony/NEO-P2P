package com.neop2p.data.p2p

/**
 * Restore policy: a loadable identity must never be silently overwritten.
 * A locked/invalidated identity (KeyStore auth-gated or lock-screen change)
 * is NOT loadable — restore is the only recovery, so it stays allowed.
 */
object RestoreGuard {
    fun allowRestore(existingLoadable: Boolean, force: Boolean): Boolean =
        force || !existingLoadable
}
