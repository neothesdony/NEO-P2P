package com.neop2p.data.p2p

/**
 * Host-provided persistence for the identity blob.
 *
 * `:app` backs this with Android KeyStore + SharedPreferences (`KeyStorePrefsCipher`),
 * while the headless `:admind` daemon backs it with a passphrase-encrypted file. The
 * port deliberately speaks [IdentityBlob] — the same payload `IdentityBlobCodec`
 * already encodes — so both hosts persist the same format and a store can be reused
 * across hosts without a data migration.
 */
interface SecretStore {
    /** The stored identity, or null when none is persisted. */
    fun load(): IdentityBlob?

    /** Persist [blob], replacing any existing secret. */
    fun save(blob: IdentityBlob)

    /** Remove the persisted secret (idempotent). */
    fun clear()
}
