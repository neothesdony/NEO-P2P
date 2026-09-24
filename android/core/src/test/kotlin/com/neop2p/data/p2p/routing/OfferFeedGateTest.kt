package com.neop2p.data.p2p.routing

import com.neop2p.data.p2p.RnsOfferDigest
import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
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
    fun `resolved dispute deletes an observer offer without a tombstone`() {
        // 2026-09-13: the arbitrator (3rd device) has no escrow row, so
        // healTerminalOfferStatuses() cannot clear its observer offer row.
        // A resolved dispute is authoritative — the finished trade must
        // leave the arbitrator's feed even if the creator's tombstone was
        // never processed.
        assertTrue(
            OfferFeedGate.resolvedDisputeDeletesOffer(
                disputeResolved = true,
                localStatus = "ESCROWED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_arbitrator"
            )
        )
        // Unresolved dispute changes nothing.
        assertFalse(
            OfferFeedGate.resolvedDisputeDeletesOffer(
                disputeResolved = false,
                localStatus = "ESCROWED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_arbitrator"
            )
        )
        // Party rows are kept as history (same rule as the tombstone path).
        assertFalse(
            OfferFeedGate.resolvedDisputeDeletesOffer(
                disputeResolved = true,
                localStatus = "ESCROWED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_seller"
            )
        )
        assertFalse(
            OfferFeedGate.resolvedDisputeDeletesOffer(
                disputeResolved = true,
                localStatus = "ESCROWED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_buyer"
            )
        )
        // Already-terminal or missing rows are no-ops.
        assertFalse(
            OfferFeedGate.resolvedDisputeDeletesOffer(
                disputeResolved = true,
                localStatus = "COMPLETED",
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_arbitrator"
            )
        )
        assertFalse(
            OfferFeedGate.resolvedDisputeDeletesOffer(
                disputeResolved = true,
                localStatus = null,
                creatorPeerId = "peer_seller",
                matchedPeerId = "peer_buyer",
                myPeerId = "peer_arbitrator"
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

    @Test
    fun `stale creator nickname is refreshed from the ingested offer`() {
        // 2026-09-25: the creator IS the sending peer and the offer is
        // digest-verified, so a non-blank payload nickname is authoritative
        // and must REPLACE a stale stored value — otherwise the peer row keeps
        // a nickname that no longer matches the announced commitment.
        assertEquals("oneplus", OfferFeedGate.effectiveCreatorNickname("Anonymous", "oneplus"))
        assertEquals("oneplus", OfferFeedGate.effectiveCreatorNickname(null, "oneplus"))
        // A blank payload nickname must never clobber a stored value.
        assertEquals("oneplus", OfferFeedGate.effectiveCreatorNickname("oneplus", ""))
        assertEquals("", OfferFeedGate.effectiveCreatorNickname(null, ""))
    }

    @Test
    fun `a stale creator nickname no longer causes an endless digest refetch loop`() {
        // storedDigestHash() rebuilds the commitment hash from the cached
        // creator nickname (OfferRouter), so a stale non-blank nickname made
        // EVERY announce look like a changed offer and re-fetch forever. Model
        // the receiver: announce → compare → refetch → re-ingest → compare.
        val offer = TradeOffer(
            offerId = "offer_loop",
            creatorPeerId = "12D3KooWCreator",
            type = OfferType.SELL,
            fiatAmount = 12_139_714L,
            cryptoAmountSats = 800_000L,
            pricePerUnit = 1_517_464_336.0,
            feeSats = 4_000L,
            fiatMethods = listOf("bca"),
            status = OfferStatus.OPEN,
            createdAt = 1_790_272_026_620L,
        )
        fun committedHash(nickname: String): String =
            Json.parseToJsonElement(RnsOfferDigest.encode(offer, nickname))
                .jsonObject["h"]!!.jsonPrimitive.content

        val announcedHash = committedHash("oneplus")
        var cachedNickname = "Anonymous" // stale non-blank value
        var refetches = 0
        repeat(5) {
            if (committedHash(cachedNickname) == announcedHash) return@repeat
            refetches++
            // The receiver re-fetches and re-ingests the served offer.
            cachedNickname = OfferFeedGate.effectiveCreatorNickname(cachedNickname, "oneplus")
        }
        assertEquals("a stale nickname must self-heal after a single refetch", 1, refetches)
    }
}
