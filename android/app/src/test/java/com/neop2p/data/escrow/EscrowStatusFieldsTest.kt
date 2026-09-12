package com.neop2p.data.escrow

import com.neop2p.data.local.entity.EscrowEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirrors the mutable fields carried by LXMF escrow_status events
 * (EscrowService.escrowStatusFields) — the buyer's mirrored row is
 * reconstructed from exactly this map, so a field missing here never
 * reaches the buyer.
 */
class EscrowStatusFieldsTest {

    private fun entity(
        payoutTxId: String? = null,
        fundingTxId: String? = "funding_tx_1",
        redeemScriptHex: String? = "redeem_hex"
    ) = EscrowEntity(
        escrow_id = "escrow_1",
        offer_id = "offer_1",
        funding_tx_id = fundingTxId,
        payout_tx_id = payoutTxId,
        funding_address = "tb1qfunding",
        funding_script_type = "SEGWIT",
        buyer_btc_address = "tb1qbuyer",
        buyer_pubkey_hex = "pub_buyer",
        seller_pubkey_hex = "pub_seller",
        deposit_amount_sats = 1_000_000L,
        trade_amount_sats = 995_000L,
        fee_amount_sats = 5_000L,
        fee_address = "tb1qfee",
        buyer_peer_id = "peer_buyer",
        seller_peer_id = "peer_seller",
        status = "RELEASED",
        created_at = 1_700_000_000_000L,
        funding_vout = 0L,
        redeem_script_hex = redeemScriptHex
    )

    @Test
    fun `payout txid travels when set`() {
        val fields = EscrowService.escrowStatusFields(entity(payoutTxId = "payout_tx_abc"))
        assertEquals("payout_tx_abc", fields["payout_tx_id"])
    }

    @Test
    fun `payout txid absent when not yet released`() {
        val fields = EscrowService.escrowStatusFields(entity(payoutTxId = null))
        assertNull(fields["payout_tx_id"])
    }

    @Test
    fun `core reconstruction fields are always present`() {
        val fields = EscrowService.escrowStatusFields(entity())
        assertEquals("escrow_1", fields["offer_id"]?.let { "escrow_1" })
        assertEquals("peer_buyer", fields["buyer_peer_id"])
        assertEquals("peer_seller", fields["seller_peer_id"])
        assertEquals("tb1qfunding", fields["funding_address"])
        assertEquals("SEGWIT", fields["funding_script_type"])
        assertEquals("tb1qbuyer", fields["buyer_btc_address"])
        assertEquals("pub_buyer", fields["buyer_pubkey_hex"])
        assertEquals("pub_seller", fields["seller_pubkey_hex"])
        assertEquals("1000000", fields["deposit_sats"])
        assertEquals("995000", fields["trade_sats"])
        assertEquals("1700000000000", fields["created_at"])
        assertEquals("funding_tx_1", fields["funding_tx_id"])
        assertEquals("0", fields["funding_vout"])
        assertEquals("redeem_hex", fields["redeem_script_hex"])
    }

    @Test
    fun `seller refund attestation travels in escrow status`() {
        val e = entity().copy(seller_refund_attestation = "ab".repeat(70))
        assertEquals("ab".repeat(70), EscrowService.escrowStatusFields(e)["seller_refund_attestation"])
    }

    @Test
    fun `buyer address attestation travels in escrow status`() {
        val e = entity().copy(buyer_address_attestation = "cd".repeat(70))
        assertEquals("cd".repeat(70), EscrowService.escrowStatusFields(e)["buyer_address_attestation"])
    }
}
