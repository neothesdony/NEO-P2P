package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerBindingTest {
    private val priv = ByteArray(32) { (it + 1).toByte() }          // deterministic test key
    private val pub = KeyDerivation.ed25519Public(priv)
    private val otherPriv = ByteArray(32) { (it + 9).toByte() }

    private fun bind(): String {
        val peerId = KeyDerivation.deriveLibp2pPeerIdFromPublicKey(pub)
        return PeerBinding.sign(priv, PeerBinding.message(peerId, "AABB", "CCDD"))
    }

    @Test fun `round-trip verifies`() {
        val peerId = KeyDerivation.deriveLibp2pPeerIdFromPublicKey(pub)
        assertTrue(PeerBinding.verify(pub.toHex(), bind(), peerId, "AABB", "CCDD"))
    }

    @Test fun `wrong identity hash fails`() {
        val peerId = KeyDerivation.deriveLibp2pPeerIdFromPublicKey(pub)
        assertFalse(PeerBinding.verify(pub.toHex(), bind(), peerId, "FFFF", "CCDD"))
    }

    @Test fun `wrong dest hash fails`() {
        val peerId = KeyDerivation.deriveLibp2pPeerIdFromPublicKey(pub)
        assertFalse(PeerBinding.verify(pub.toHex(), bind(), peerId, "AABB", "FFFF"))
    }

    @Test fun `claimed peerId not derived from pubkey fails`() {
        assertFalse(PeerBinding.verify(pub.toHex(), bind(), "12D3KooWSPOOFEDSPOOFE", "AABB", "CCDD"))
    }

    @Test fun `signature from another key fails`() {
        val peerId = KeyDerivation.deriveLibp2pPeerIdFromPublicKey(pub)
        val sig = PeerBinding.sign(otherPriv, PeerBinding.message(peerId, "AABB", "CCDD"))
        assertFalse(PeerBinding.verify(pub.toHex(), sig, peerId, "AABB", "CCDD"))
    }
}
