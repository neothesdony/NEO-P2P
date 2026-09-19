package com.neop2p.data.escrow

import com.neop2p.NeoP2PConfig
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params

/**
 * The mainnet/testnet3 -> [NetworkParameters] mapping (Phase 1c).
 *
 * `:core` accepts `net: NetworkParameters` everywhere (`ResolutionGuard`,
 * `EscrowScriptGate`, `ReleaseIntegrity`, `EscrowTxBuilder`) but had no
 * provider — the only one was `EscrowService.NET_PARAMS` in `:app`. The
 * headless `:admind` daemon needs the same mapping without the Android host,
 * and a party-supplied dispute is signed with the params of the chain the
 * operator selected (`--network`).
 *
 * Mirrors `EscrowService.NET_PARAMS`: anything other than `"mainnet"` is
 * testnet3.
 */
object NetworkParams {
    fun forName(network: String): NetworkParameters =
        if (network == "mainnet") MainNetParams.get() else TestNet3Params.get()

    /** The params for the host's active [NeoP2PConfig.network]. */
    fun current(): NetworkParameters = forName(NeoP2PConfig.network)
}
