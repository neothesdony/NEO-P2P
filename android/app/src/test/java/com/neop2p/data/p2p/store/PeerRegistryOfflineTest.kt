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
}
