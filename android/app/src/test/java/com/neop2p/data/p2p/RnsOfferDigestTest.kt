package com.neop2p.data.p2p

import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RnsOfferDigest] — the offer-feed announce digest (Phase 3).
 *
 * G1 invariant: the digest is a COMMITMENT ONLY. RNS announces are broadcast
 * in cleartext to every peer on the mesh and to the transport node, so the
 * digest must never carry trade data (amounts, methods, nickname, peerId).
 * The full public subset travels over encrypted LXMF and is verified against
 * the commitment before ingest.
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
    fun `digest carries no trade data - G1 invariant`() {
        val offer = sampleOffer()
        val digest = RnsOfferDigest.encode(offer, "Anonymous")
        // The announce payload must not leak amounts, methods, nickname,
        // peerId, or expiry — only version, offer id, and the commitment.
        assertFalse("fiat amount leaked", digest.contains("\"f\""))
        assertFalse("crypto amount leaked", digest.contains("\"s\""))
        assertFalse("price leaked", digest.contains("\"p\""))
        assertFalse("methods leaked", digest.contains("\"m\""))
        assertFalse("nickname leaked", digest.contains("\"n\""))
        assertFalse("creator peerId leaked", digest.contains("\"c\""))
        assertFalse("expiry leaked", digest.contains("\"x\""))
        assertFalse("plaintext fiat_amount leaked", digest.contains("1000000"))
        assertFalse("plaintext sats leaked", digest.contains("100000"))
        assertFalse("plaintext method leaked", digest.contains("bca"))
        assertFalse("plaintext nickname leaked", digest.contains("Anonymous"))
        assertTrue("digest must carry the commitment", digest.contains("\"h\""))
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
    fun `canonical json round-trips through the commitment`() {
        val offer = sampleOffer()
        val digest = RnsOfferDigest.decode(RnsOfferDigest.encode(offer, "Anonymous"))!!
        val served = RnsOfferDigest.canonicalJson(offer, "Anonymous")
        assertTrue("served JSON must verify against the digest commitment", RnsOfferDigest.verify(served, digest))
    }

    @Test
    fun `tampered served json fails the commitment`() {
        val offer = sampleOffer()
        val digest = RnsOfferDigest.decode(RnsOfferDigest.encode(offer, "Anonymous"))!!
        val tampered = RnsOfferDigest.canonicalJson(offer, "Anonymous")
            .replace("\"fiat_amount\":1000000", "\"fiat_amount\":999999")
        assertFalse("tampered JSON must fail verification", RnsOfferDigest.verify(tampered, digest))
    }

    @Test
    fun `canonical json carries methods and nickname for the feed card`() {
        val offer = sampleOffer()
        val served = RnsOfferDigest.canonicalJson(offer, "Anonymous")
        assertTrue("methods must ride the encrypted fetch", served.contains("\"fiat_methods\""))
        assertTrue("nickname must ride the encrypted fetch", served.contains("\"nickname\""))
        assertTrue("methods content present", served.contains("bca"))
        assertTrue("nickname content present", served.contains("Anonymous"))
        // P0-1: payment details never leave the device.
        assertFalse("payment details must not be served", served.contains("payment_details"))
        assertFalse("btc receive address must not be served", served.contains("btc_receive_address"))
    }

    @Test
    fun `malformed digest decodes to null`() {
        assertNull(RnsOfferDigest.decode("not json"))
        assertNull(RnsOfferDigest.decode("{\"v\":99,\"id\":\"x\"}"))
        assertNull(RnsOfferDigest.decode(""))
    }

    @Test
    fun `digest without expiry still verifies`() {
        val offer = sampleOffer().copy(expiresAt = null)
        val digest = RnsOfferDigest.decode(RnsOfferDigest.encode(offer))!!
        assertTrue(RnsOfferDigest.verify(RnsOfferDigest.canonicalJson(offer), digest))
    }
}
