package com.neop2p.data.p2p

/**
 * Pure restoration helpers for a persisted [IdentityBlob]. The nickname is
 * stored alongside the seed but must be normalized on the way back in — the
 * blob may be absent (legacy), blank, or carry control characters from an old
 * build. Kept in :core so the same rule runs in `:app` and `:admind`.
 */
object IdentityRestore {
    const val DEFAULT_NICKNAME = "Anonymous"

    /** The nickname to restore: sanitized, never blank. */
    fun nickname(stored: String?): String =
        IdentityDerivation.sanitizeNickname(stored.orEmpty()).ifEmpty { DEFAULT_NICKNAME }
}
