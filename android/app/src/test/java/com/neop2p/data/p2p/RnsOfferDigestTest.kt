package com.neop2p.data.p2p

import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RnsOfferDigest] — the compact offer digest that rides the
 * RNS announce appData (Phase 3).
 *
 * The digest must stay well under the announce appData budget (~300 bytes:
 * MTU 500 − header 19 − announce overhead ~180 with ratchet) so the feed
 * works over real RNS links.
 */
class RnsOfferDigestTest {

    private fun sampleOffer(): TradeOffer = TradeOffer(
        offerId = "offer_1750000000000",
        creatorPeerId = "12D3KooWQmNvYqK4xK5yL8zT2aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4yZ5",
        type = OfferType.SELL,
        fiatAmount = 1_000_000L,
        cryptoAmountSats = 100_000L,
        pricePerUnit = 10_000_000.0,
        feeSats = 500L,
        fiatMethods = listOf("bca", "qris", "gopay"),
        status = OfferStatus.OPEN,
        createdAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 86_400_000L
    )

    @Test
    fun `digest fits the announce appData budget`() {
        val digest = RnsOfferDigest.encode(sampleOffer(), "Anonymous")
        val bytes = digest.toByteArray(Charsets.UTF_8)
        // MTU 500 − HEADER_MIN 19 − announce overhead (64 pubkey + 10 name
        // hash + 10 random hash + 32 ratchet + 64 sig = 180) = 301 bytes.
        assertTrue("digest must fit announce budget, was ${bytes.size}", bytes.size <= 301)
    }

    @Test
    fun `digest round-trips the offer id`() {
        val offer = sampleOffer()
        val digest = RnsOfferDigest.encode(offer, "Anonymous")
        val decoded = RnsOfferDigest.decode(digest)
        assertNotNull(decoded)
        assertEquals(offer.offerId, RnsOfferDigest.offerIdOf(decoded!!))
    }

    @Test
    fun `malformed digest decodes to null`() {
        assertNull(RnsOfferDigest.decode("not json"))
        assertNull(RnsOfferDigest.decode("{\"v\":99,\"id\":\"x\"}"))
        assertNull(RnsOfferDigest.decode(""))
    }

    @Test
    fun `digest without expiry omits the field`() {
        val offer = sampleOffer().copy(expiresAt = null)
        val digest = RnsOfferDigest.encode(offer)
        assertTrue(!digest.contains("\"x\""))
    }
}
