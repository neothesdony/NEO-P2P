package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferIngestGateTest {

    @Test
    fun `verified sender is its own author`() {
        assertEquals("peerA", SignalingSenderGate.authorOf(true, "peerA"))
    }

    @Test
    fun `unverified sender has no author`() {
        assertNull(SignalingSenderGate.authorOf(false, "peerA"))
    }

    @Test
    fun `blank peer id has no author`() {
        assertNull(SignalingSenderGate.authorOf(true, ""))
    }

    @Test
    fun `offer with matching digest peer is accepted`() {
        assertTrue(OfferIngestGate.shouldIngest("offer_1", true, "peerA", "peerA"))
    }

    @Test
    fun `unsolicited offer with no digest is rejected`() {
        assertFalse(OfferIngestGate.shouldIngest("offer_1", false, null, "peerA"))
    }

    @Test
    fun `offer whose digest belongs to another peer is rejected`() {
        assertFalse(OfferIngestGate.shouldIngest("offer_1", true, "peerB", "peerA"))
    }

    @Test
    fun `offer with blank id is rejected`() {
        assertFalse(OfferIngestGate.shouldIngest("", true, "peerA", "peerA"))
        assertFalse(OfferIngestGate.shouldIngest(null, true, "peerA", "peerA"))
    }

    @Test
    fun `creator equal to source is accepted`() {
        assertTrue(OfferIngestGate.creatorIsSource("peerA", "peerA"))
    }

    @Test
    fun `creator different from source is rejected`() {
        assertFalse(OfferIngestGate.creatorIsSource("victim", "attacker"))
    }

    @Test
    fun `blank creator is rejected`() {
        assertFalse(OfferIngestGate.creatorIsSource("", "attacker"))
    }
}
