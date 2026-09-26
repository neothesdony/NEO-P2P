package com.neop2p.data.p2p

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure arbitration wire parsing + ingest decisions. This is the executable
 * spec of the guard order currently inlined in
 * `P2POrchestrator.applyDisputeEvent` / `applyEvidenceEvent` — the app and the
 * headless arbitrator daemon must decide identically.
 */
class ArbitrationIngestTest {

    private val now = 1_700_000_000_000L

    private fun disputeJson(
        escrowId: String? = "esc-1",
        openedBy: String = "buyer",
        reason: String = "not paid",
        openedAt: Long? = 1234L,
        buyerPeerId: String? = "buyer",
        sellerPeerId: String? = "seller",
        extra: String = "",
    ): String = buildString {
        append("{")
        escrowId?.let { append("\"escrow_id\":\"").append(it).append("\",") }
        append("\"opened_by\":\"").append(openedBy).append("\",")
        append("\"reason\":\"").append(reason).append("\"")
        openedAt?.let { append(",\"opened_at\":").append(it) }
        buyerPeerId?.let { append(",\"buyer_peer_id\":\"").append(it).append("\"") }
        sellerPeerId?.let { append(",\"seller_peer_id\":\"").append(it).append("\"") }
        if (extra.isNotEmpty()) append(",").append(extra)
        append("}")
    }

    private fun record(
        resolved: Boolean = false,
        buyerBtcAddress: String? = null,
        buyerPubkeyHex: String? = null,
        sellerPubkeyHex: String? = null,
        sellerRefundAttestation: String? = null,
        buyerAddressAttestation: String? = null,
        offerId: String? = null,
        tradeSats: Long? = null,
        redeemScriptHex: String? = null,
        psbtHex: String? = null,
        refundTxHex: String? = null,
        depositSats: Long? = null,
        fundingScriptType: String? = null,
        sellerRefundAddress: String? = null,
    ): DisputeRecord = DisputeRecord(
        escrowId = "esc-1",
        openedBy = "buyer",
        reason = "not paid",
        openedAt = 1234L,
        redeemScriptHex = redeemScriptHex,
        psbtHex = psbtHex,
        refundTxHex = refundTxHex,
        depositSats = depositSats,
        fundingScriptType = fundingScriptType,
        sellerRefundAddress = sellerRefundAddress,
        buyerPeerId = "buyer",
        sellerPeerId = "seller",
        buyerBtcAddress = buyerBtcAddress,
        buyerPubkeyHex = buyerPubkeyHex,
        sellerPubkeyHex = sellerPubkeyHex,
        sellerRefundAttestation = sellerRefundAttestation,
        buyerAddressAttestation = buyerAddressAttestation,
        offerId = offerId,
        tradeSats = tradeSats,
        receivedAt = 1L,
        resolved = resolved,
    )

    // ── parseDispute ────────────────────────────────────────────────────

    @Test
    fun `parseDispute reads the wire fields`() {
        val parsed = ArbitrationIngest.parseDispute(
            disputeJson(extra = "\"redeem_script_hex\":\"aa\",\"psbt_hex\":\"bb\",\"refund_tx_hex\":\"cc\"," +
                "\"deposit_sats\":\"4321\",\"funding_script_type\":\"P2WSH\"," +
                "\"seller_refund_address\":\"bc1qrefund\",\"trade_sats\":\"9000\",\"offer_id\":\"off-1\"")
        )!!
        assertEquals("esc-1", parsed.escrowId)
        assertEquals("buyer", parsed.openedBy)
        assertEquals("not paid", parsed.reason)
        assertEquals(1234L, parsed.openedAt)
        assertEquals("aa", parsed.redeemScriptHex)
        assertEquals("bb", parsed.psbtHex)
        assertEquals("cc", parsed.refundTxHex)
        assertEquals(4321L, parsed.depositSats)
        assertEquals("P2WSH", parsed.fundingScriptType)
        assertEquals("bc1qrefund", parsed.sellerRefundAddress)
        assertEquals("buyer", parsed.buyerPeerId)
        assertEquals("seller", parsed.sellerPeerId)
        assertEquals(9000L, parsed.tradeSats)
        assertEquals("off-1", parsed.offerId)
    }

    @Test
    fun `parseDispute reads quoted numeric fields`() {
        // RnsSession.sendDispute string-escapes every field, so deposit_sats
        // and trade_sats arrive quoted on the wire.
        val parsed = ArbitrationIngest.parseDispute(
            disputeJson(extra = "\"deposit_sats\":\"100\",\"trade_sats\":\"250\"")
        )!!
        assertEquals(100L, parsed.depositSats)
        assertEquals(250L, parsed.tradeSats)
    }

    @Test
    fun `parseDispute returns null without escrow_id`() {
        assertNull(ArbitrationIngest.parseDispute(disputeJson(escrowId = null)))
    }

    @Test
    fun `parseDispute tolerates absent optional fields`() {
        val parsed = ArbitrationIngest.parseDispute(
            disputeJson(escrowId = "esc-2", openedBy = "", openedAt = null, buyerPeerId = null, sellerPeerId = null)
        )!!
        assertEquals("", parsed.openedBy)
        assertNull(parsed.buyerPeerId)
        assertNull(parsed.sellerPeerId)
        assertNull(parsed.depositSats)
        assertNull(parsed.offerId)
    }

    @Test
    fun `parseDispute defaults opened_at to now when absent`() {
        val before = System.currentTimeMillis()
        val parsed = ArbitrationIngest.parseDispute(disputeJson(openedAt = null))!!
        assertTrue(parsed.openedAt >= before)
    }

    @Test
    fun `dispute wire carries funding outpoint and script template`() {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"escrow_id":"e1","funding_txid":"ab","funding_vout":1,
                "script_template":"MULTISIG_2OF3_CLTV_V1","cltv_locktime":1790000000}"""
        ).jsonObject
        val parsed = ArbitrationIngest.parseDispute(obj)!!
        assertEquals("ab", parsed.fundingTxid)
        assertEquals(1, parsed.fundingVout)
        assertEquals("MULTISIG_2OF3_CLTV_V1", parsed.scriptTemplate)
        assertEquals(1_790_000_000L, parsed.cltvLocktime)
    }

    // ── decideDispute guard order ───────────────────────────────────────

    private fun inbound(
        openedBy: String = "buyer",
        buyerPeerId: String? = "buyer",
        sellerPeerId: String? = "seller",
        redeemScriptHex: String? = null,
        psbtHex: String? = null,
        refundTxHex: String? = null,
        depositSats: Long? = null,
        fundingScriptType: String? = null,
        sellerRefundAddress: String? = null,
    ) = InboundDispute(
        escrowId = "esc-1", openedBy = openedBy, reason = "r", openedAt = 1L,
        redeemScriptHex = redeemScriptHex, psbtHex = psbtHex, refundTxHex = refundTxHex,
        depositSats = depositSats,
        fundingScriptType = fundingScriptType, sellerRefundAddress = sellerRefundAddress,
        buyerPeerId = buyerPeerId, sellerPeerId = sellerPeerId,
        buyerBtcAddress = null, buyerPubkeyHex = null, sellerPubkeyHex = null,
        sellerRefundAttestation = null, buyerAddressAttestation = null, offerId = null, tradeSats = null,
    )

    @Test
    fun `resolved dispute redelivery is dropped`() {
        val d = ArbitrationIngest.decideDispute(inbound(), "buyer", record(resolved = true), { 0 }, now)
        assertTrue(d is DisputeIngestDecision.Drop)
        assertFalse((d as DisputeIngestDecision.Drop).warn)
    }

    @Test
    fun `new dispute with mismatched opened_by is dropped`() {
        val d = ArbitrationIngest.decideDispute(inbound(openedBy = "attacker"), "buyer", null, { 0 }, now)
        assertTrue(d is DisputeIngestDecision.Drop)
    }

    @Test
    fun `existing dispute republished by the counterparty passes`() {
        // healDisputePsbt republishes a blank-psbt dispute from the OTHER device,
        // whose peerId != opened_by — must be accepted.
        val d = ArbitrationIngest.decideDispute(inbound(), "seller", record(), { 0 }, now)
        assertTrue(d is DisputeIngestDecision.Accept)
        assertFalse((d as DisputeIngestDecision.Accept).isNew)
    }

    @Test
    fun `dispute from a non-party sender is dropped`() {
        val d = ArbitrationIngest.decideDispute(inbound(), "stranger", null, { 0 }, now)
        assertTrue(d is DisputeIngestDecision.Drop)
    }

    @Test
    fun `new dispute with no party ids is dropped`() {
        val d = ArbitrationIngest.decideDispute(
            inbound(buyerPeerId = null, sellerPeerId = null), "buyer", null, { 0 }, now
        )
        assertTrue(d is DisputeIngestDecision.Drop)
    }

    @Test
    fun `new dispute at the unresolved cap is dropped`() {
        val d = ArbitrationIngest.decideDispute(inbound(), "buyer", null, { 25 }, now)
        assertTrue(d is DisputeIngestDecision.Drop)
    }

    @Test
    fun `new dispute below the cap is accepted`() {
        val d = ArbitrationIngest.decideDispute(inbound(), "buyer", null, { 24 }, now)
        assertTrue(d is DisputeIngestDecision.Accept)
        assertTrue((d as DisputeIngestDecision.Accept).isNew)
    }

    @Test
    fun `existing dispute at the cap still passes`() {
        val d = ArbitrationIngest.decideDispute(inbound(), "buyer", record(), { 25 }, now)
        assertTrue(d is DisputeIngestDecision.Accept)
    }

    @Test
    fun `the unresolved count is only read once the sender is a party`() {
        // A stranger must never trigger even a count query (F4 work ordering).
        var strangerCounts = 0
        ArbitrationIngest.decideDispute(
            inbound(), "stranger", null, unresolvedFromSender = { strangerCounts++; 0 }, nowMs = now,
        )
        assertEquals(0, strangerCounts)

        var partyCounts = 0
        val d = ArbitrationIngest.decideDispute(
            inbound(), "buyer", null, unresolvedFromSender = { partyCounts++; 0 }, nowMs = now,
        )
        assertTrue(d is DisputeIngestDecision.Accept)
        assertEquals(1, partyCounts)
    }

    // ── merge / F2 preservation ─────────────────────────────────────────

    @Test
    fun `accepted dispute carries the merged record with resolved false`() {
        val d = ArbitrationIngest.decideDispute(inbound(), "buyer", record(resolved = false), { 0 }, now)
            as DisputeIngestDecision.Accept
        assertEquals("esc-1", d.record.escrowId)
        assertEquals(now, d.record.receivedAt)
        assertFalse(d.record.resolved)
    }

    @Test
    fun `merge preserves F2 fields omitted by a re-delivery`() {
        val existing = record(
            buyerBtcAddress = "bc1qbuyer", buyerPubkeyHex = "02aa", sellerPubkeyHex = "02bb",
            sellerRefundAttestation = "att-seller", buyerAddressAttestation = "att-buyer",
            offerId = "off-1", tradeSats = 9000L,
        )
        val merged = ArbitrationIngest.mergeDispute(existing, inbound(), now)
        assertEquals("bc1qbuyer", merged.buyerBtcAddress)
        assertEquals("02aa", merged.buyerPubkeyHex)
        assertEquals("02bb", merged.sellerPubkeyHex)
        assertEquals("att-seller", merged.sellerRefundAttestation)
        assertEquals("att-buyer", merged.buyerAddressAttestation)
        assertEquals("off-1", merged.offerId)
        assertEquals(9000L, merged.tradeSats)
    }

    @Test
    fun `merge overwrites F2 fields present on the wire`() {
        val existing = record(buyerBtcAddress = "bc1qold", offerId = "off-old", tradeSats = 1L)
        val fresh = inbound().copy(
            buyerBtcAddress = "bc1qnew", offerId = "off-new", tradeSats = 42L
        )
        val merged = ArbitrationIngest.mergeDispute(existing, fresh, now)
        assertEquals("bc1qnew", merged.buyerBtcAddress)
        assertEquals("off-new", merged.offerId)
        assertEquals(42L, merged.tradeSats)
    }

    @Test
    fun `merge preserves the party ids omitted by a partial re-delivery`() {
        // 2026-09-26: buyer_peer_id/seller_peer_id decide where the arbitrator
        // delivers a resolution. A re-delivery carrying only the sender's id
        // must not null the counterparty id — the resolution would then reach
        // only one party and funds could stay locked.
        val existing = record()
        val partial = inbound().copy(buyerPeerId = null, sellerPeerId = "seller")
        val merged = ArbitrationIngest.mergeDispute(existing, partial, now)
        assertEquals("buyer", merged.buyerPeerId)
        assertEquals("seller", merged.sellerPeerId)
    }

    @Test
    fun `merge with no existing record keeps wire nulls`() {
        val merged = ArbitrationIngest.mergeDispute(null, inbound(), now)
        assertNull(merged.buyerBtcAddress)
        assertNull(merged.offerId)
        assertFalse(merged.resolved)
    }

    @Test
    fun `merge preserves a payout and refund tx omitted by a re-delivery`() {
        // 2026-09-26: a partial re-delivery (e.g. the 60s dispute retry) must
        // never wipe a tx a prior delivery already supplied — the arbitrator
        // cannot rule a refund without refund_tx_hex.
        val existing = record(
            redeemScriptHex = "aa", psbtHex = "bb", refundTxHex = "cc",
            depositSats = 4321L, fundingScriptType = "P2WSH", sellerRefundAddress = "bc1qrefund",
        )
        val merged = ArbitrationIngest.mergeDispute(existing, inbound(), now)
        assertEquals("aa", merged.redeemScriptHex)
        assertEquals("bb", merged.psbtHex)
        assertEquals("cc", merged.refundTxHex)
        assertEquals(4321L, merged.depositSats)
        assertEquals("P2WSH", merged.fundingScriptType)
        assertEquals("bc1qrefund", merged.sellerRefundAddress)
    }

    @Test
    fun `merge overwrites a payout and refund tx present on the wire`() {
        val existing = record(psbtHex = "old-psbt", refundTxHex = "old-refund")
        val fresh = inbound().copy(psbtHex = "new-psbt", refundTxHex = "new-refund")
        val merged = ArbitrationIngest.mergeDispute(existing, fresh, now)
        assertEquals("new-psbt", merged.psbtHex)
        assertEquals("new-refund", merged.refundTxHex)
    }

    // ── evidence ────────────────────────────────────────────────────────

    private fun evidence(imageBase64: String = "", submitter: String = "buyer") = InboundEvidence(
        escrowId = "esc-1", submitter = submitter, description = "receipt",
        mimeType = "image/jpeg", imageBase64 = imageBase64,
    )

    @Test
    fun `evidence submitter must be the sender`() {
        val d = ArbitrationIngest.decideEvidence(evidence(submitter = "stranger"), "buyer", { true }, { false })
        assertTrue(d is EvidenceIngestDecision.Drop)
    }

    @Test
    fun `stranger evidence never reads a store`() {
        // The original returned before touching escrow state; getEscrow has a
        // resume-heal side effect, so a stranger must not trigger it.
        var disputeReads = 0
        var escrowReads = 0
        val d = ArbitrationIngest.decideEvidence(
            evidence(submitter = "stranger"), "buyer",
            hasDisputeRow = { disputeReads++; true },
            hasLocalEscrow = { escrowReads++; true },
        )
        assertTrue(d is EvidenceIngestDecision.Drop)
        assertEquals(0, disputeReads)
        assertEquals(0, escrowReads)
    }

    @Test
    fun `evidence for an unknown escrow is dropped`() {
        val d = ArbitrationIngest.decideEvidence(evidence(), "buyer", { false }, { false })
        assertTrue(d is EvidenceIngestDecision.Drop)
    }

    @Test
    fun `evidence is accepted when the dispute row exists`() {
        val d = ArbitrationIngest.decideEvidence(evidence(), "buyer", { true }, { false })
        assertTrue(d is EvidenceIngestDecision.Accept)
    }

    @Test
    fun `evidence is accepted when the local escrow exists`() {
        val d = ArbitrationIngest.decideEvidence(evidence(), "buyer", { false }, { true })
        assertTrue(d is EvidenceIngestDecision.Accept)
    }

    @Test
    fun `blank evidence image is accepted with null bytes`() {
        val d = ArbitrationIngest.decideEvidence(evidence(imageBase64 = ""), "buyer", { true }, { false })
            as EvidenceIngestDecision.Accept
        assertNull(d.imageData)
    }

    @Test
    fun `valid evidence image is decoded`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val d = ArbitrationIngest.decideEvidence(evidence(imageBase64 = b64), "buyer", { true }, { false })
            as EvidenceIngestDecision.Accept
        assertArrayEquals(bytes, d.imageData!!)
        assertEquals("image/jpeg", d.mimeType)
        assertEquals("receipt", d.description)
    }

    @Test
    fun `oversized evidence is dropped`() {
        val oversized = "A".repeat(ArbitrationIngest.MAX_EVIDENCE_BASE64_CHARS + 1)
        val d = ArbitrationIngest.decideEvidence(evidence(imageBase64 = oversized), "buyer", { true }, { false })
        assertTrue(d is EvidenceIngestDecision.Drop)
    }

    @Test
    fun `garbage evidence image is accepted with null bytes`() {
        // Matches the app: a non-base64 image is runCatching'd to null and the
        // row is simply not persisted (no Drop, no notification).
        val d = ArbitrationIngest.decideEvidence(evidence(imageBase64 = "!!!not base64!!!"), "buyer", { true }, { false })
            as EvidenceIngestDecision.Accept
        assertNull(d.imageData)
    }

    @Test
    fun `decodeEvidenceImage rejects oversize blank and bad base64`() {
        assertNull(ArbitrationIngest.decodeEvidenceImage(""))
        assertNull(ArbitrationIngest.decodeEvidenceImage("A".repeat(ArbitrationIngest.MAX_EVIDENCE_BASE64_CHARS + 1)))
        assertNull(ArbitrationIngest.decodeEvidenceImage("!!!not base64!!!"))
        assertArrayEquals(byteArrayOf(9), ArbitrationIngest.decodeEvidenceImage("CQ==")!!)
    }

    @Test
    fun `shouldProcess mirrors the resolved re-delivery gate`() {
        assertTrue(ArbitrationIngest.shouldProcess(alreadyResolved = false))
        assertFalse(ArbitrationIngest.shouldProcess(alreadyResolved = true))
    }

    @Test
    fun `parseEvidence reads the wire fields and defaults`() {
        val parsed = ArbitrationIngest.parseEvidence(
            "{\"escrow_id\":\"esc-1\",\"submitter\":\"buyer\",\"description\":\"receipt\",\"image_base64\":\"CQ==\"}"
        )!!
        assertEquals("esc-1", parsed.escrowId)
        assertEquals("buyer", parsed.submitter)
        assertEquals("receipt", parsed.description)
        assertEquals("image/jpeg", parsed.mimeType)
        assertEquals("CQ==", parsed.imageBase64)
        assertNull(ArbitrationIngest.parseEvidence("{\"submitter\":\"buyer\"}"))
    }
}
