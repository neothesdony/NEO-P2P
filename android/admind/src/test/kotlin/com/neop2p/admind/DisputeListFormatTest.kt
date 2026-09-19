package com.neop2p.admind

import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.data.p2p.EvidenceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisputeListFormatTest {

    private fun record(
        escrowId: String = "esc-1",
        openedBy: String = "12D3KooWBuyerPeerIdLong",
        resolved: Boolean = false,
    ) = DisputeRecord(
        escrowId = escrowId, openedBy = openedBy, reason = "not paid", openedAt = 1234L,
        redeemScriptHex = null, psbtHex = null, refundTxHex = null, depositSats = 4321L,
        fundingScriptType = "P2WSH", sellerRefundAddress = null,
        buyerPeerId = "12D3KooWBuyerPeerIdLong", sellerPeerId = "12D3KooWSellerPeerLong",
        buyerBtcAddress = "bc1qbuyer", buyerPubkeyHex = "02aa", sellerPubkeyHex = "02bb",
        sellerRefundAttestation = "att", buyerAddressAttestation = "att",
        offerId = "off-1", tradeSats = 9000L, receivedAt = 200L, resolved = resolved,
    )

    private fun evidence() = EvidenceRecord(
        evidenceId = "ev-1", escrowId = "esc-1", submitterPeerId = "buyer",
        description = "receipt", mimeType = "image/jpeg",
        imageData = ByteArray(2048), submittedAt = 1L,
    )

    @Test
    fun `row shows status evidence count and a truncated opener`() {
        val line = DisputeListFormat.row(record(), evidenceCount = 2)
        assertTrue(line.startsWith("esc-1  [OPEN]"))
        assertTrue(line.contains("by=12D3KooWBuye…"))
        assertTrue(line.contains("opened_at=1234"))
        assertTrue(line.contains("evidence=2"))
    }

    @Test
    fun `row marks a resolved dispute`() {
        assertTrue(DisputeListFormat.row(record(resolved = true), 0).contains("[resolved]"))
    }

    @Test
    fun `detail lists reason parties amounts and evidence metadata without bytes`() {
        val lines = DisputeListFormat.detail(record(), listOf(evidence()))
        assertEquals("  reason: not paid", lines[0])
        assertTrue(lines[1].contains("buyer: 12D3KooWBuye…"))
        assertTrue(lines[2].contains("deposit_sats: 4321"))
        assertTrue(lines[2].contains("trade_sats: 9000"))
        assertTrue(lines[2].contains("offer_id: off-1"))
        assertTrue(lines[3].contains("ev-1"))
        assertTrue(lines[3].contains("image/jpeg"))
        assertTrue(lines[3].contains("2048B"))
        // Never dump image bytes.
        lines.forEach { assertFalse(it.contains("0x")) }
    }

    @Test
    fun `detail handles a null party id`() {
        val lines = DisputeListFormat.detail(
            record().copy(buyerPeerId = null, offerId = null, tradeSats = null),
            emptyList(),
        )
        assertTrue(lines[1].contains("buyer: null…"))
        assertTrue(lines[2].contains("offer_id: null"))
        assertEquals(3, lines.size)
    }
}
