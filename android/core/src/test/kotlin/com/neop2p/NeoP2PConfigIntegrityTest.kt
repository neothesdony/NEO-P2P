package com.neop2p

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the signature-protected constants in NeoP2PConfig.kt.
 * A paste error (address/pubkey/signature mismatch) fails here immediately,
 * before any escrow can be created. android.util.Log is a no-op in unit
 * tests, so the verify methods run cleanly on the JVM.
 */
class NeoP2PConfigIntegrityTest {

    @Test
    fun `fee wallet signature verifies against embedded address`() {
        assertTrue(
            "Fee wallet address, signer pubkey, and signature must be self-consistent",
            NeoP2PConfig.verifyFeeWalletIntegrity()
        )
    }

    @Test
    fun `arbitrator signature verifies against embedded pubkey`() {
        assertTrue(
            "Arbitrator pubkey and signature must be self-consistent",
            NeoP2PConfig.verifyArbitratorIntegrity()
        )
    }
}
