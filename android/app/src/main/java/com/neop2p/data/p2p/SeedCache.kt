package com.neop2p.data.p2p

import com.neop2p.domain.model.BitcoinAddressType
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.ECKey

/**
 * Caches the stretched BIP-39 seed and the per-index Bitcoin keys for one
 * identity.
 *
 * [IdentityManager.currentSeed] used to run PBKDF2-HMAC-SHA512 (2048 rounds)
 * on every call, so an N-address HD scan stretched the mnemonic N times. This
 * cache stretches once per identity and memoizes each `(index, internal)`
 * derivation.
 *
 * [invalidate] MUST be called on every identity change (restore / reset) — a
 * cached seed that survives a restore hands out addresses from the previous
 * identity.
 *
 * Pure JVM: the expensive [stretch] step is injected so the policy is testable
 * without Android/KeyStore.
 */
class SeedCache(
    private val stretch: (List<String>) -> ByteArray
) {
    companion object {
        /** BIP-44 Bitcoin path for [index]; [internal] selects the `/1` change chain. */
        fun bitcoinPath(index: Int, internal: Boolean): String =
            "m/44'/0'/0'/${if (internal) 1 else 0}/$index"

        /**
         * Render a 32-byte private key as [type]'s address under [params]. Pure
         * — extracted so indexed derivation is testable without [IdentityManager]'s
         * Android `Context`.
         */
        fun addressFor(
            type: BitcoinAddressType,
            privateKey: ByteArray,
            params: NetworkParameters
        ): String {
            val key = ECKey.fromPrivate(privateKey)
            return when (type) {
                BitcoinAddressType.LEGACY -> LegacyAddress.fromKey(params, key).toBase58()
                BitcoinAddressType.SEGWIT -> SegwitAddress.fromKey(params, key).toBech32()
            }
        }

        private const val INTERNAL_FLAG = 1 shl 30
    }

    private var cachedMnemonic: List<String>? = null
    private var cachedSeed: ByteArray? = null
    private val keys = HashMap<Int, ByteArray>()

    /** Number of times the mnemonic was stretched. Test/diagnostics only. */
    @Volatile
    var stretchCount: Int = 0
        private set

    /**
     * The cached BIP-39 seed for [mnemonic]. Stretches only when the mnemonic
     * changes. The returned array is the cache's own buffer — callers must not
     * mutate it (public accessors copy).
     */
    @Synchronized
    fun seedFor(mnemonic: List<String>): ByteArray {
        val current = cachedSeed
        if (current != null && cachedMnemonic == mnemonic) return current
        val stretched = stretch(mnemonic)
        cachedSeed = stretched
        cachedMnemonic = mnemonic
        keys.values.forEach { it.fill(0) }
        keys.clear()
        stretchCount++
        return stretched
    }

    /**
     * Memoized 32-byte Bitcoin private key at `m/44'/0'/0'/{0|1}/index`.
     * Returns a fresh copy the caller owns (and may wipe).
     */
    @Synchronized
    fun bitcoinKey(mnemonic: List<String>, index: Int, internal: Boolean): ByteArray {
        // Resolve the seed FIRST: a changed mnemonic clears the memo below.
        val seed = seedFor(mnemonic)
        val memo = if (internal) index or INTERNAL_FLAG else index
        keys[memo]?.let { return it.copyOf() }
        val key = KeyDerivation.deriveSecp256k1(seed, bitcoinPath(index, internal))
        keys[memo] = key
        return key.copyOf()
    }

    /** Drop and wipe the cached seed + keys. */
    @Synchronized
    fun invalidate() {
        cachedSeed?.fill(0)
        cachedSeed = null
        cachedMnemonic = null
        keys.values.forEach { it.fill(0) }
        keys.clear()
    }
}
