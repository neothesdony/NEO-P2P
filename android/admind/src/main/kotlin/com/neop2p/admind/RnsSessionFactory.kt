package com.neop2p.admind

import com.neop2p.NeoP2PConfig
import com.neop2p.data.p2p.IdentityBlob
import com.neop2p.data.p2p.RnsSession
import java.nio.file.Path

/**
 * Builds the headless RNS/LXMF session for the arbitrator identity (Phase 1c),
 * shared by `serve` and `resolve` so both announce and address peers identically.
 */
object RnsSessionFactory {

    fun create(dataDir: Path, blob: IdentityBlob): RnsSession {
        val derived = ArbitratorUnlock.derive(blob)
        return RnsSession(
            configDir = dataDir.resolve("reticulum").toString(),
            seed = ArbitratorUnlock.rnsIdentitySeed(blob),
            myPeerId = blob.peerId,
            transportNodes = transportNodes(),
            // The daemon runs on a laptop; LAN AutoInterface is the phone's
            // discovery mechanism, not the arbitrator's.
            enableAutoInterface = false,
            libp2pPrivKey = derived.libp2pPrivateKey,
        )
    }

    fun transportNodes(): List<Pair<String, Int>> = buildList {
        if (NeoP2PConfig.RNS_TRANSPORT_NODE_HOST.isNotBlank()) {
            add(NeoP2PConfig.RNS_TRANSPORT_NODE_HOST to NeoP2PConfig.RNS_TRANSPORT_NODE_PORT)
        }
        if (NeoP2PConfig.SECONDARY_TRANSPORT_NODE_HOST.isNotBlank()) {
            add(NeoP2PConfig.SECONDARY_TRANSPORT_NODE_HOST to NeoP2PConfig.SECONDARY_TRANSPORT_NODE_PORT)
        }
    }
}
