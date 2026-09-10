package com.neop2p

import org.bitcoinj.core.Address
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fee wallet is a hardcoded address that the payout tx pays via
 * Address.fromString(NET_PARAMS, ...) (EscrowService.generatePayoutTransaction).
 * A mainnet bc1... address on a testnet build (or vice versa) throws
 * InvalidCharacter at payout build time and NO trade can complete.
 * Regression for cfaa566 (2026-09-09): mainnet fee wallet landed on main
 * (NETWORK=testnet), breaking every testnet payout.
 */
class FeeWalletNetworkCompatibilityTest {

    @Test
    fun `fee wallet parses under the build's network params`() {
        val params = if (BuildConfig.NETWORK == "mainnet") MainNetParams.get() else TestNet3Params.get()
        val parsed = runCatching {
            Address.fromString(params, NeoP2PConfig.FEE_WALLET_ADDRESS)
        }
        assertTrue(
            "FEE_WALLET_ADDRESS ${NeoP2PConfig.FEE_WALLET_ADDRESS} must parse under " +
                "NETWORK=${BuildConfig.NETWORK} (bitcoinj error: ${parsed.exceptionOrNull()})",
            parsed.isSuccess
        )
    }
}
