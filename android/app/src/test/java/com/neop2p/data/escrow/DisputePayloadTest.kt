package com.neop2p.data.escrow

import com.neop2p.data.local.PendingDisputeStore.PendingDispute
import com.neop2p.domain.model.Escrow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 2026-09-26: the dispute wire map is one builder shared by the manual path,
 * the automatic sweep escalation, and the 60s retry. An auto-dispute that
 * dropped `refund_tx_hex` left the arbitrator unable to rule a refund
 * (`escrow_offer_1790376777696_1790381499906`).
 */
class DisputePayloadTest {

    private fun pending(psbtHex: String? = null, refundTxHex: String? = null) = PendingDispute(
        escrowId = "esc-1",
        openedBy = "seller",
        reason = "funded_stalled_no_payment",
        redeemScriptHex = "aa",
        psbtHex = psbtHex,
        refundTxHex = refundTxHex,
        depositSats = 100_000L,
        fundingScriptType = "LEGACY",
        sellerRefundAddress = "tb1qrefund",
    )

    private val escrow = Escrow(
        escrowId = "esc-1",
        offerId = "off-1",
        fundingTxId = "txid-1",
        fundingVout = 2,
        redeemScriptHex = "aa",
        depositAmountSats = 100_000,
        tradeAmountSats = 99_000,
        feeAmountSats = 500,
        buyerPeerId = "buyer",
        sellerPeerId = "seller",
        buyerBtcAddress = "tb1qbuyer",
        sellerRefundAddress = "tb1qrefund",
        fundedAmountSats = 100_000,
        buyerPubKeyHex = "02bb",
        sellerPubKeyHex = "02aa",
        sellerRefundAttestation = "att-seller",
        buyerAddressAttestation = "att-buyer",
        scriptTemplate = EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1,
        cltvLocktime = 1_790_000_000L,
    )

    @Test
    fun `wire fields ship the refund and payout tx when present`() {
        val fields = pending(psbtHex = "psbt", refundTxHex = "refund").toWireFields()
        assertEquals("psbt", fields["psbt_hex"])
        assertEquals("refund", fields["refund_tx_hex"])
    }

    @Test
    fun `wire fields omit a tx that is absent`() {
        val fields = pending(psbtHex = null, refundTxHex = "refund").toWireFields()
        assertFalse(fields.containsKey("psbt_hex"))
        assertEquals("refund", fields["refund_tx_hex"])
    }

    @Test
    fun `assembled auto-dispute carries both unsigned txs`() {
        val p = assembleDisputePending(escrow, "seller", "funded_stalled_no_payment", "psbt", "refund")
        assertEquals("psbt", p.psbtHex)
        assertEquals("refund", p.refundTxHex)
        val fields = p.toWireFields(escrow)
        assertEquals("psbt", fields["psbt_hex"])
        assertEquals("refund", fields["refund_tx_hex"])
    }

    @Test
    fun `wire fields carry the parties and the script template from the live escrow`() {
        val fields = assembleDisputePending(escrow, "seller", "r", null, "refund").toWireFields(escrow)
        assertEquals("buyer", fields["buyer_peer_id"])
        assertEquals("seller", fields["seller_peer_id"])
        assertEquals("MULTISIG_2OF3_CLTV_V1", fields["script_template"])
        assertEquals("2", fields["funding_vout"])
        assertEquals("tb1qbuyer", fields["buyer_btc_address"])
    }

    @Test
    fun `wire fields fall back to the live escrow for a legacy pending row`() {
        val fields = pending(psbtHex = null, refundTxHex = null).toWireFields(escrow)
        assertEquals("tb1qbuyer", fields["buyer_btc_address"])
        assertEquals("att-seller", fields["seller_refund_attestation"])
    }
}
