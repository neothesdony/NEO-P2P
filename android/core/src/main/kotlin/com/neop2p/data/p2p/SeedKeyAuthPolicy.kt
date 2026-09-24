package com.neop2p.data.p2p

/**
 * 2026-09-24: `KeyStoreAesGcmCipher` samples `isDeviceSecure` once, at first
 * key generation. An identity created before a lock screen exists keeps a
 * permanently un-gated seed key; adding a PIN later does not upgrade it. This
 * pure predicate drives a one-time re-wrap on the next unlocked load.
 */
object SeedKeyAuthPolicy {
    fun needsRetrofit(deviceSecure: Boolean, keyAuthBound: Boolean): Boolean =
        deviceSecure && !keyAuthBound
}
