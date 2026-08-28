package com.neop2p.data.p2p.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two-taker collision decision tests (pure gate — mirrors
 * EscrowRouterApplyTest style; the DAO CAS itself is exercised on-device).
 */
class OfferClaimGateTest {

    // ── effectiveStatus ──

    @Test
    fun `open offer adopts remote status`() {
        assertEquals("MATCHED", OfferClaimGate.effectiveStatus(
            "OPEN", null, "MATCHED", "peerB", "peerB", "seller"
        ))
    }

    @Test
    fun `terminal statuses are locked`() {
        assertNull(OfferClaimGate.effectiveStatus(
            "CANCELLED", null, "MATCHED", "peerB", "peerB", "seller"
        ))
        assertNull(OfferClaimGate.effectiveStatus(
            "COMPLETED", null, "OPEN", null, "seller", "seller"
        ))
    }

    @Test
    fun `locked offer unlocks only by creator`() {
        // stranger tries to unlock
        assertNull(OfferClaimGate.effectiveStatus(
            "MATCHED", "peerA", "OPEN", null, "peerB", "seller"
        ))
        // creator declines → unlock
        assertEquals("OPEN", OfferClaimGate.effectiveStatus(
            "MATCHED", "peerA", "OPEN", null, "seller", "seller"
        ))
    }

    @Test
    fun `matched to escrowed applies forward`() {
        assertEquals("ESCROWED", OfferClaimGate.effectiveStatus(
            "MATCHED", "peerA", "ESCROWED", null, "seller", "seller"
        ))
    }

    @Test
    fun `escrowed to matched replay rejected`() {
        // Stale MATCHED replay after ESCROWED: status must NOT downgrade —
        // it stays ESCROWED (the match itself is protected by adoptMatchedPeer).
        assertEquals("ESCROWED", OfferClaimGate.effectiveStatus(
            "ESCROWED", "peerA", "MATCHED", "peerB", "peerB", "seller"
        ))
    }

    @Test
    fun `second matched event keeps locked status`() {
        // local MATCHED (already claimed) + another MATCHED → keep MATCHED
        assertEquals("MATCHED", OfferClaimGate.effectiveStatus(
            "MATCHED", "peerA", "MATCHED", "peerB", "peerB", "seller"
        ))
    }

    @Test
    fun `unknown local row takes remote status`() {
        assertEquals("MATCHED", OfferClaimGate.effectiveStatus(
            null, null, "MATCHED", "peerB", "peerB", "seller"
        ))
    }

    // ── adoptMatchedPeer ──

    @Test
    fun `first claim fills blank match`() {
        assertEquals("peerB", OfferClaimGate.adoptMatchedPeer("OPEN", null, "peerB", "me"))
        assertEquals("peerB", OfferClaimGate.adoptMatchedPeer("OPEN", "", "peerB", "me"))
    }

    @Test
    fun `blank remote never overwrites`() {
        assertNull(OfferClaimGate.adoptMatchedPeer("MATCHED", "peerA", null, "me"))
        assertNull(OfferClaimGate.adoptMatchedPeer("MATCHED", "peerA", "", "me"))
    }

    @Test
    fun `self claim loses to relayed winner`() {
        // I claimed peerA (=me); relay delivered peerB's MATCHED → I lost
        assertEquals("peerB", OfferClaimGate.adoptMatchedPeer("MATCHED", "me", "peerB", "me"))
    }

    @Test
    fun `third party match never overwrites anothers match`() {
        // local match belongs to peerA (not me) — a stranger's event must not flip it
        assertNull(OfferClaimGate.adoptMatchedPeer("MATCHED", "peerA", "peerB", "me"))
        assertNull(OfferClaimGate.adoptMatchedPeer("MATCHED", "peerA", "peerA", "me"))
    }

    @Test
    fun `own relayed event is a no-op`() {
        // my own MATCHED event echoes back — local already equals remote
        assertNull(OfferClaimGate.adoptMatchedPeer("MATCHED", "me", "me", "me"))
    }

    @Test
    fun `escrowed match is settled and never flipped`() {
        // A stale MATCHED replay after the escrow exists must NOT flip the
        // buyer the escrow was built for — even if the local match is me.
        assertNull(OfferClaimGate.adoptMatchedPeer("ESCROWED", "me", "peerB", "me"))
    }

    @Test
    fun `open offer adopts any first claim`() {
        assertEquals("peerX", OfferClaimGate.adoptMatchedPeer("OPEN", null, "peerX", "me"))
    }
}
