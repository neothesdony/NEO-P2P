package com.neop2p.data.p2p

/**
 * Thrown when the identity seed is encrypted with an auth-gated KeyStore key
 * (P0-4) and the device has not been unlocked within the validity window.
 *
 * Callers must NEVER treat this as "no identity" — generating a replacement
 * would destroy the original identity's derived keys and trade history.
 * Surface an unlock prompt (BiometricPrompt / device credential) and retry.
 */
open class IdentityLockedException(
    message: String = "Identity is locked behind device authentication. Unlock the device and retry."
) : IllegalStateException(message)

/**
 * Thrown when a stored identity blob exists but cannot be read (corrupt,
 * truncated, or written by an incompatible version). Restoring from the
 * recovery phrase is the only recovery — generating a replacement would
 * destroy the existing identity's derived keys and trade history.
 */
class IdentityRestoreRequiredException(
    message: String = "The stored identity could not be read. Restore it from your recovery phrase."
) : IdentityLockedException(message)
