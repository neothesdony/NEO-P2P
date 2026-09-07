package com.neop2p.data.p2p.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferFeedGateLostClaimTest {

    @Test
    fun `acceptor row targets the creator`() {
        assertEquals(
            "seller",
            OfferFeedGate.lostMatchTarget(
                status = "MATCHED",
                matchedPeerId = "buyer",
                creatorPeerId = "seller",
                myPeerId = "buyer"
            )
        )
    }

    @Test
    fun `only MATCHED rows qualify`() {
        assertNull(
            OfferFeedGate.lostMatchTarget("OPEN", "buyer", "seller", "buyer")
        )
        assertNull(
            OfferFeedGate.lostMatchTarget("ESCROWED", "buyer", "seller", "buyer")
        )
        assertNull(
            OfferFeedGate.lostMatchTarget("COMPLETED", "buyer", "seller", "buyer")
        )
        assertNull(
            OfferFeedGate.lostMatchTarget(null, "buyer", "seller", "buyer")
        )
    }

    @Test
    fun `rows where I am not the matched peer do not qualify`() {
        assertNull(
            OfferFeedGate.lostMatchTarget("MATCHED", "someone-else", "seller", "buyer")
        )
        assertNull(
            OfferFeedGate.lostMatchTarget("MATCHED", null, "seller", "buyer")
        )
    }

    @Test
    fun `the creator is never a self-send target`() {
        // Single-key demo / same-seed devices: myPeerId can equal the creator's.
        assertNull(
            OfferFeedGate.lostMatchTarget("MATCHED", "seller", "seller", "seller")
        )
        // Case-insensitive peer-id comparison, same as canViewOfferDetail.
        assertEquals(
            "SELLER",
            OfferFeedGate.lostMatchTarget("MATCHED", "BUYER", "SELLER", "buyer")
        )
        assertEquals(
            "SELLER",
            OfferFeedGate.lostMatchTarget("MATCHED", "buyer", "SELLER", "buyer")
        )
    }

    @Test
    fun `blank myPeerId never qualifies`() {
        assertNull(
            OfferFeedGate.lostMatchTarget("MATCHED", "buyer", "seller", "")
        )
    }

    @Test
    fun `missing creator never qualifies`() {
        assertNull(
            OfferFeedGate.lostMatchTarget("MATCHED", "buyer", null, "buyer")
        )
        assertNull(
            OfferFeedGate.lostMatchTarget("MATCHED", "buyer", "", "buyer")
        )
    }

    @Test
    fun `lost-claim filter matches the republish target semantics`() {
        // The old bug: filter matched_peer_id == myPeerId then send to
        // matched_peer_id (itself). The gate must select the same row but
        // target the creator — verify both halves in one expression.
        val myPeerId = "buyer"
        val row = mapOf(
            "status" to "MATCHED",
            "matchedPeerId" to "buyer",
            "creatorPeerId" to "seller"
        )
        val target = OfferFeedGate.lostMatchTarget(
            row["status"],
            row["matchedPeerId"],
            row["creatorPeerId"],
            myPeerId
        )
        assertEquals("seller", target)
        // And the target is NEVER the row's own matched peer (self-send bug).
        val selfTarget = row["matchedPeerId"]!!
        assert(target != selfTarget)
    }
}
