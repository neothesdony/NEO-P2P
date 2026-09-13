package com.neop2p.data.p2p.routing

import com.neop2p.data.local.entity.EscrowEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the remote escrow status transition rules in EscrowRouter
 * (Task 8): remote kind:33337 events may advance the happy path but never
 * downgrade a locked/terminal status, and only for escrows the local
 * identity is a party to.
 */
class EscrowRouterApplyTest {

    private fun row(
        buyer: String = "peer_buyer",
        seller: String = "peer_seller"
    ) = EscrowEntity(
        escrow_id = "escrow_1",
        offer_id = "offer_1",
        deposit_amount_sats = 1_000_000L,
        trade_amount_sats = 995_000L,
        fee_amount_sats = 5_000L,
        fee_address = "tb1qfee",
        buyer_peer_id = buyer,
        seller_peer_id = seller
    )

    @Test
    fun `happy path transitions apply`() {
        assertEquals("FUNDED", EscrowRouter.applyRemoteStatus("FUNDING", "FUNDED", false))
        assertEquals("PAYMENT_PENDING", EscrowRouter.applyRemoteStatus("FUNDED", "PAYMENT_PENDING", false))
        assertEquals("RECEIPT_SENT", EscrowRouter.applyRemoteStatus("PAYMENT_PENDING", "RECEIPT_SENT", false))
        assertEquals("CONFIRMING", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "CONFIRMING", false))
        // Release is a happy-path terminal outcome: the seller's broadcast
        // must converge the buyer's mirrored row (regression: RELEASED was
        // missing from ALLOWED_REMOTE + the forward order, so the buyer
        // stayed "Paid — awaiting seller release" forever).
        assertEquals("RELEASED", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "RELEASED", false))
        assertEquals("RELEASED", EscrowRouter.applyRemoteStatus("CONFIRMING", "RELEASED", false))
        assertEquals("DISPUTED", EscrowRouter.applyRemoteStatus("CONFIRMING", "DISPUTED", false))
    }

    @Test
    fun `signed is a forward state between funded and payment pending`() {
        assertEquals("SIGNED", EscrowRouter.applyRemoteStatus("FUNDED", "SIGNED", false))
        assertEquals("PAYMENT_PENDING", EscrowRouter.applyRemoteStatus("SIGNED", "PAYMENT_PENDING", false))
        assertEquals("RECEIPT_SENT", EscrowRouter.applyRemoteStatus("SIGNED", "RECEIPT_SENT", false))
        assertEquals("CONFIRMING", EscrowRouter.applyRemoteStatus("SIGNED", "CONFIRMING", false))
        assertNull(EscrowRouter.applyRemoteStatus("SIGNED", "FUNDED", false))
    }

    @Test
    fun `terminal statuses are locked`() {
        assertNull(EscrowRouter.applyRemoteStatus("RELEASED", "FUNDED", false))
        assertNull(EscrowRouter.applyRemoteStatus("REFUNDED", "PAYMENT_PENDING", false))
        assertNull(EscrowRouter.applyRemoteStatus("CANCELLED", "FUNDING", false))
        assertNull(EscrowRouter.applyRemoteStatus("DISPUTED", "CONFIRMING", false))
    }

    @Test
    fun `terminal outcomes may land from any non-terminal state`() {
        // The seller's sweep is the authority: auto-cancel / auto-refund /
        // dispute-resolution must converge the buyer's mirrored row even
        // when the buyer is still on an earlier state.
        assertEquals("CANCELLED", EscrowRouter.applyRemoteStatus("FUNDING", "CANCELLED", false))
        assertEquals("CANCELLED", EscrowRouter.applyRemoteStatus("FUNDED", "CANCELLED", false))
        assertEquals("REFUNDED", EscrowRouter.applyRemoteStatus("FUNDED", "REFUNDED", false))
        assertEquals("REFUNDED", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "REFUNDED", false))
    }

    @Test
    fun `funding is not disputable remotely`() {
        // FUNDING is not disputable (2026-09-05): the deposit is either not
        // yet broadcast (nothing to arbitrate) or in flight (unconfirmed —
        // the arbitrator's resolution would spend a nonexistent output). A
        // stale/forged FUNDING dispute from an older build must not flip the
        // mirrored row.
        assertNull(EscrowRouter.applyRemoteStatus("FUNDING", "DISPUTED", false))
        // Disputes from funded+ states still open.
        assertEquals("DISPUTED", EscrowRouter.applyRemoteStatus("FUNDED", "DISPUTED", false))
        assertEquals("DISPUTED", EscrowRouter.applyRemoteStatus("CONFIRMING", "DISPUTED", false))
    }

    @Test
    fun `arbitration outcomes close a disputed escrow`() {
        // After the arbitrator rules, the terminal outcome must land on the
        // counterparty's DISPUTED row — otherwise the buyer stays "In
        // dispute" forever while the seller already broadcast the payout/
        // refund (regression: DISPUTED was in TERMINAL, so the kind:33337
        // terminal publish from storeArbitrationDecision was dropped).
        assertEquals("RELEASED", EscrowRouter.applyRemoteStatus("DISPUTED", "RELEASED", false))
        assertEquals("REFUNDED", EscrowRouter.applyRemoteStatus("DISPUTED", "REFUNDED", false))
    }

    @Test
    fun `dispute cannot be cancelled remotely`() {
        // CANCELLED is never an arbitration outcome — a DISPUTED row may
        // only close via the arbitrator's RELEASED/REFUNDED.
        assertNull(EscrowRouter.applyRemoteStatus("DISPUTED", "CANCELLED", false))
        assertNull(EscrowRouter.applyRemoteStatus("DISPUTED", "FUNDED", false))
    }

    @Test
    fun `never downgrades or jumps backwards`() {
        assertNull(EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "PAYMENT_PENDING", false))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "FUNDING", false))
        assertNull(EscrowRouter.applyRemoteStatus("CONFIRMING", "FUNDED", false))
    }

    @Test
    fun `unknown or same status is a no-op`() {
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "FUNDED", false))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "BOGUS", false))
    }

    // ── F-2 (2026-09-13): the creator row refuses a remote CANCELLED ──

    @Test
    fun `a remote CANCELLED cannot terminate the creator row`() {
        // CANCELLED is authored only by the escrow's own device; pre-fix a
        // forged escrow_status stopped the sweep from ever revisiting a
        // funded escrow.
        assertNull(EscrowRouter.applyRemoteStatus("FUNDING", "CANCELLED", localIsCreator = true))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "CANCELLED", localIsCreator = true))
    }

    @Test
    fun `a remote CANCELLED still converges a mirror row`() {
        assertEquals(
            "CANCELLED",
            EscrowRouter.applyRemoteStatus("FUNDED", "CANCELLED", localIsCreator = false)
        )
    }

    @Test
    fun `a remote RELEASED still converges the creator row`() {
        // Arbitration outcomes must still land on the creator's own row.
        assertEquals(
            "RELEASED",
            EscrowRouter.applyRemoteStatus("CONFIRMING", "RELEASED", localIsCreator = true)
        )
    }

    // ── F-2: only the true counterparty may move a row ──

    @Test
    fun `a stranger cannot move an existing row`() {
        assertFalse(
            EscrowRouter.senderIsCounterparty(
                row(), senderPeerId = "peer_stranger", myPeerId = "peer_seller",
                claimedBuyerPeerId = "peer_buyer", claimedSellerPeerId = "peer_seller"
            )
        )
    }

    @Test
    fun `the buyer may move the seller's row`() {
        assertTrue(
            EscrowRouter.senderIsCounterparty(
                row(), senderPeerId = "peer_buyer", myPeerId = "peer_seller",
                claimedBuyerPeerId = "peer_buyer", claimedSellerPeerId = "peer_seller"
            )
        )
    }

    @Test
    fun `the seller may move the buyer's mirror`() {
        assertTrue(
            EscrowRouter.senderIsCounterparty(
                row(), senderPeerId = "peer_seller", myPeerId = "peer_buyer",
                claimedBuyerPeerId = "peer_buyer", claimedSellerPeerId = "peer_seller"
            )
        )
    }

    @Test
    fun `the local identity's own event is rejected`() {
        assertFalse(
            EscrowRouter.senderIsCounterparty(
                row(), senderPeerId = "peer_seller", myPeerId = "peer_seller",
                claimedBuyerPeerId = "peer_buyer", claimedSellerPeerId = "peer_seller"
            )
        )
    }

    @Test
    fun `a blank sender or self is rejected`() {
        assertFalse(
            EscrowRouter.senderIsCounterparty(
                row(), senderPeerId = "", myPeerId = "peer_seller",
                claimedBuyerPeerId = "peer_buyer", claimedSellerPeerId = "peer_seller"
            )
        )
        assertFalse(
            EscrowRouter.senderIsCounterparty(
                row(), senderPeerId = "peer_buyer", myPeerId = "",
                claimedBuyerPeerId = "peer_buyer", claimedSellerPeerId = "peer_seller"
            )
        )
    }

    @Test
    fun `a new row is accepted only when the claims name the sender and me`() {
        assertTrue(
            EscrowRouter.senderIsCounterparty(
                null, senderPeerId = "peer_buyer", myPeerId = "peer_seller",
                claimedBuyerPeerId = "peer_buyer", claimedSellerPeerId = "peer_seller"
            )
        )
        // The sender claims an id that is not itself — fail closed.
        assertFalse(
            EscrowRouter.senderIsCounterparty(
                null, senderPeerId = "peer_buyer", myPeerId = "peer_seller",
                claimedBuyerPeerId = "peer_stranger", claimedSellerPeerId = "peer_seller"
            )
        )
    }

    @Test
    fun `psbt hex payload adopts as hex-text bytes not raw binary`() {
        // C1d (2026-09-11): the wire carries the unsigned payout tx as a hex
        // STRING; the BLOB convention is hex-TEXT bytes (writers do
        // `hex.encodeToByteArray()`, consumers do `toString(UTF_8)` then hex
        // decode). The old adopt path hexToBytes()-decoded the hex string, so
        // the buyer's signTransaction re-encoded mojibake and bitcoinj died
        // with "Claimed value length too large: N".
        val wireHex = "0200000001" + "11".repeat(64) + "0000000000ffffffff01" + "2202000000000000160014" + "22".repeat(20) + "00000000"
        val blob = EscrowRouter.psbtHexToBlob(wireHex)
        // Round-trip through the consumer's read convention must reproduce the wire value.
        assertEquals(wireHex, blob.toString(Charsets.UTF_8))
        // And it must NOT equal the raw-byte interpretation (the bug).
        assertFalse(blob.contentEquals(hexToBytesCompat(wireHex)))
    }

    /** Local hex decode so the test asserts the convention without depending on bitcoinj. */
    private fun hexToBytesCompat(hex: String): ByteArray {
        val data = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    @Test
    fun `the creator never adopts a remote psbt`() {
        // F-3 (2026-09-13): the seller BUILT psbt_unsigned; a remote event must
        // never overwrite the very payout tx they later sign and broadcast.
        assertFalse(EscrowRouter.shouldAdoptRemotePsbt(localIsCreator = true, remoteHex = "02000000"))
        assertFalse(EscrowRouter.shouldAdoptRemotePsbt(localIsCreator = true, remoteHex = null))
    }

    @Test
    fun `the mirror adopts a non-blank remote psbt`() {
        assertTrue(EscrowRouter.shouldAdoptRemotePsbt(localIsCreator = false, remoteHex = "02000000"))
    }

    @Test
    fun `nobody adopts a blank or null remote psbt`() {
        assertFalse(EscrowRouter.shouldAdoptRemotePsbt(false, null))
        assertFalse(EscrowRouter.shouldAdoptRemotePsbt(false, ""))
    }
}
