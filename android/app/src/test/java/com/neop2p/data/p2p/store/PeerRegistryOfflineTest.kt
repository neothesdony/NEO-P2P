package com.neop2p.data.p2p.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRegistryOfflineTest {

    @Test
    fun `peer seen then marked offline is not online`() {
        val reg = PeerRegistry()
        reg.recordPeerSeen("peer1")
        assertTrue(reg.isPeerOnline("peer1"))
        reg.markPeerOffline("peer1")
        assertFalse(reg.isPeerOnline("peer1"))
    }

    @Test
    fun `unknown peer is never online`() {
        val reg = PeerRegistry()
        assertFalse(reg.isPeerOnline("ghost"))
    }

    @Test
    fun `markAllOffline drops every peer`() {
        val reg = PeerRegistry()
        reg.recordPeerSeen("peer1")
        reg.recordPeerSeen("peer2")
        assertTrue(reg.isPeerOnline("peer1"))
        assertTrue(reg.isPeerOnline("peer2"))
        reg.markAllOffline()
        assertFalse(reg.isPeerOnline("peer1"))
        assertFalse(reg.isPeerOnline("peer2"))
        assertTrue(reg.connectedPeerCount() == 0)
    }

    @Test
    fun `inbound message re-raises peer to online after markAllOffline`() {
        val reg = PeerRegistry()
        reg.recordPeerSeen("peer1")
        reg.markAllOffline()
        assertFalse(reg.isPeerOnline("peer1"))
        reg.recordPeerSeen("peer1")
        assertTrue(reg.isPeerOnline("peer1"))
    }

    @Test
    fun `relay traffic never downgrades a direct peer`() {
        val reg = PeerRegistry()
        reg.recordPeerSeen("peer1", authenticated = true)
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.DIRECT)
        // Relay peer_list / echoed messages arrive with authenticated=false.
        reg.recordPeerSeen("peer1", authenticated = false)
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.DIRECT)
    }

    @Test
    fun `relay-only peer is reconnecting after relay drop`() {
        val reg = PeerRegistry()
        reg.recordPeerSeen("peer1", authenticated = false)
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.RELAYED)
        // A relay drop means no peer can be assumed reachable; the backoff
        // loop will re-raise them. OFFLINE is reserved for unknown peers and
        // for stale DIRECT claims revoked when the libp2p link closes.
        reg.markAllOffline()
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.RECONNECTING)
        // Next relay contact re-raises to RELAYED.
        reg.recordPeerSeen("peer1", authenticated = false)
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.RELAYED)
    }

    @Test
    fun `direct peer survives relay drop until the link actually closes`() {
        val reg = PeerRegistry()
        reg.recordPeerSeen("peer1", authenticated = true)
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.DIRECT)
        // A relay drop must NOT downgrade a live direct session.
        reg.markAllOffline()
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.DIRECT)
        // markPeerOffline (called by the libp2p sweep when the connection
        // closes) is what revokes the stale DIRECT claim.
        reg.markPeerOffline("peer1")
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.OFFLINE)
    }

    @Test
    fun `quota-exceeded peer is gated as non-direct and recovers on contact`() {
        val reg = PeerRegistry()
        reg.recordPeerSeen("peer1", authenticated = false)
        reg.markPeerQuotaExceeded("peer1")
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.RELAY_QUOTA)
        reg.recordPeerSeen("peer1", authenticated = false)
        assertTrue(reg.qualityOf("peer1") == PeerRegistry.ConnectionQuality.RELAYED)
    }

    @Test
    fun `unknown peer quality is offline`() {
        val reg = PeerRegistry()
        assertTrue(reg.qualityOf("ghost") == PeerRegistry.ConnectionQuality.OFFLINE)
    }
}
