package com.neop2p.data.p2p.routing

import com.neop2p.data.p2p.RnsOfferDigest
import com.neop2p.domain.model.OfferStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OfferFeedGate] — the offer-feed digest consumer decisions
 * (2026-09-02: 3rd-device convergence).
 *
 * The bug: a completed trade synced the two participants (LXMF offer_status),
 * but non-participant peers never learned the status change — the creator
 * stopped re-announcing the offer and receivers skipped already-known
 * digests, so the offer stayed OPEN on the 3rd device forever.
 */
class OfferFeedGateTest {

    private fun liveDigest(offerId: String, hash: String): JsonObject =
        Json.parseToJsonElement("""{"v":1,"id":"$offerId","h":"$hash"}""").jsonObject

    @Test
    fun `tombstone digest never triggers a refetch`() {
        val tombstone = Json.parseToJsonElement(
            RnsOfferDigest.encodeTombstone("offer_x")
        ).jsonObject
        assertTrue(RnsOfferDigest.isTombstone(tombstone))
        assertFalse(OfferFeedGate.needsReFetch("OPEN", "deadbeef", tombstone))
        assertFalse(OfferFeedGate.needsReFetch("MATCHED", "deadbeef", tombstone))
        assertFalse(OfferFeedGate.needsReFetch(null, null, tombstone))
    }

    @Test
    fun `same hash never refetches - pacing loop echo`() {
        assertFalse(OfferFeedGate.needsReFetch("OPEN", "abc123", liveDigest("offer_x", "abc123")))
        // Locked rows too — a stale OPEN-era digest must not churn a MATCHED row.
        assertFalse(OfferFeedGate.needsReFetch("MATCHED", "abc123", liveDigest("offer_x", "abc123")))
    }

    @Test
    fun `hash change triggers refetch`() {
        assertTrue(OfferFeedGate.needsReFetch("OPEN", "oldhash", liveDigest("offer_x", "newhash")))
        // Status change = hash change (canonical JSON carries status) — the
        // exact 3rd-device scenario: stored OPEN, announcer now COMPLETED.
        assertTrue(OfferFeedGate.needsReFetch("OPEN", "hash_of_open_json", liveDigest("offer_x", "hash_of_completed_json")))
    }

    @Test
    fun `no local row is never refetched or tombstoned`() {
        assertFalse(OfferFeedGate.needsReFetch(null, null, liveDigest("offer_x", "whatever")))
        assertFalse(OfferFeedGate.acceptTombstone(null))
    }

    @Test
    fun `missing hash fields are treated as no-op`() {
        val noHash = Json.parseToJsonElement("""{"v":1,"id":"offer_x"}""").jsonObject
        assertFalse(OfferFeedGate.needsReFetch("OPEN", "abc", noHash))
    }

    @Test
    fun `tombstone applies only to a held non-terminal row`() {
        assertTrue(OfferFeedGate.acceptTombstone("OPEN"))
        assertTrue(OfferFeedGate.acceptTombstone("MATCHED"))
        assertTrue(OfferFeedGate.acceptTombstone("ESCROWED"))
        assertTrue(OfferFeedGate.acceptTombstone("PAUSED"))
        assertFalse(OfferFeedGate.acceptTombstone("COMPLETED"))
        assertFalse(OfferFeedGate.acceptTombstone("CANCELLED"))
    }

    @Test
    fun `observer row is deleted by a terminal tombstone`() {
        // 3rd phone: neither creator nor matched peer — the finished
        // trade's offer must disappear from the feed entirely.
        assertTrue(
            OfferFeedGate.tombstoneDeletesRow(
                localStatus = "MATCHED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_observer"
            )
        )
        assertTrue(
            OfferFeedGate.tombstoneDeletesRow(
                localStatus = "OPEN",
                creatorPeerId = "peer_seller",
                matchedPeerId = null,
                myPeerId = "peer_observer"
            )
        )
    }

    @Test
    fun `party rows are kept - marked terminal, never deleted`() {
        // Creator's own row is their history.
        assertFalse(
            OfferFeedGate.tombstoneDeletesRow(
                localStatus = "MATCHED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_seller"
            )
        )
        // The buyer's escrow detail reads fiat + bank details from the
        // offer row — deleting it would break the completed-escrow view.
        assertFalse(
            OfferFeedGate.tombstoneDeletesRow(
                localStatus = "ESCROWED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_buyer"
            )
        )
    }

    @Test
    fun `tombstone deletion never applies to terminal or missing rows`() {
        assertFalse(
            OfferFeedGate.tombstoneDeletesRow(
                localStatus = "COMPLETED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_observer"
            )
        )
        assertFalse(
            OfferFeedGate.tombstoneDeletesRow(
                localStatus = null,
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_observer"
            )
        )
        // Blank local identity can never authorize a deletion.
        assertFalse(
            OfferFeedGate.tombstoneDeletesRow(
                localStatus = "MATCHED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = ""
            )
        )
    }

    @Test
    fun `tombstone round-trips through encode and decode`() {
        val encoded = RnsOfferDigest.encodeTombstone("offer_1750000000000")
        val decoded = RnsOfferDigest.decode(encoded)
        assertNotNull(decoded)
        assertEquals("offer_1750000000000", RnsOfferDigest.offerIdOf(decoded!!))
        assertTrue(RnsOfferDigest.isTombstone(decoded))
        // G1: the tombstone must not leak status, peerId, or amounts.
        assertFalse(encoded.contains("COMPLETED"))
        assertFalse(encoded.contains("CANCELLED"))
        assertFalse(encoded.contains("peerId"))
    }

    @Test
    fun `live digest is not a tombstone`() {
        assertFalse(RnsOfferDigest.isTombstone(liveDigest("offer_x", "abc")))
    }
}
