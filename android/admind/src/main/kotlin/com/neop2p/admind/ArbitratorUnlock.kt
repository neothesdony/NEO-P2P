package com.neop2p.admind

import com.neop2p.NeoP2PConfig
import com.neop2p.data.p2p.Bip39
import com.neop2p.data.p2p.DerivedIdentity
import com.neop2p.data.p2p.IdentityBlob
import com.neop2p.data.p2p.IdentityDerivation

/**
 * Fail-closed arbitrator identity unlock.
 *
 * The daemon must never start on a mnemonic that is not the configured arbitrator
 * (`NeoP2PConfig.ARBITRATOR_PUBKEY`) or on a build whose embedded arbitrator key has
 * been tampered with (`NeoP2PConfig.verifyArbitratorIntegrity`). Any mismatch throws —
 * there is no warning-only path and no fallback identity.
 */
object ArbitratorUnlock {

    /** Validates the checksum, stretches the mnemonic, derives all keys. */
    fun derive(blob: IdentityBlob): DerivedIdentity {
        val seed = seedFor(blob)
        return try {
            IdentityDerivation.derive(seed)
        } finally {
            seed.fill(0)
        }
    }

    /** The parity-normalized arbitrator x-only pubkey hex for this mnemonic. */
    fun arbitratorPubKeyHex(blob: IdentityBlob): String {
        val seed = seedFor(blob)
        return try {
            IdentityDerivation.arbitratorPubKeyHex(seed)
        } finally {
            seed.fill(0)
        }
    }

    fun isArbitrator(blob: IdentityBlob): Boolean =
        arbitratorPubKeyHex(blob).equals(NeoP2PConfig.ARBITRATOR_PUBKEY, ignoreCase = true)

    /** Throws [IllegalStateException] unless the mnemonic IS the arbitrator. */
    fun requireArbitrator(blob: IdentityBlob) {
        val derived = arbitratorPubKeyHex(blob)
        if (!derived.equals(NeoP2PConfig.ARBITRATOR_PUBKEY, ignoreCase = true)) {
            // Only a prefix of each key is shown — never the mnemonic or a full key.
            throw IllegalStateException(
                "This mnemonic is not the configured arbitrator " +
                    "(derived ${derived.take(12)}…, expected ${NeoP2PConfig.ARBITRATOR_PUBKEY.take(12)}…). " +
                    "Refusing to unlock."
            )
        }
    }

    /** Throws unless the embedded arbitrator signature verifies. */
    fun requireIntegrity() {
        if (!NeoP2PConfig.verifyArbitratorIntegrity()) {
            throw IllegalStateException("Arbitrator integrity signature failed — refusing to start")
        }
    }

    private fun seedFor(blob: IdentityBlob): ByteArray {
        require(Bip39.validateChecksum(blob.seedPhrase)) {
            "Invalid BIP-39 checksum in stored identity"
        }
        return Bip39.mnemonicToSeed(blob.seedPhrase)
    }
}
