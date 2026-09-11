package com.neop2p.data.p2p.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirrors the remote escrow status transition rules in EscrowRouter
 * (Task 8): remote kind:33337 events may advance the happy path but never
 * downgrade a locked/terminal status, and only for escrows the local
 * identity is a party to.
 */
class EscrowRouterApplyTest {

    @Test
    fun `happy path transitions apply`() {
        assertEquals("FUNDED", EscrowRouter.applyRemoteStatus("FUNDING", "FUNDED"))
        assertEquals("PAYMENT_PENDING", EscrowRouter.applyRemoteStatus("FUNDED", "PAYMENT_PENDING"))
        assertEquals("RECEIPT_SENT", EscrowRouter.applyRemoteStatus("PAYMENT_PENDING", "RECEIPT_SENT"))
        assertEquals("CONFIRMING", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "CONFIRMING"))
        // Release is a happy-path terminal outcome: the seller's broadcast
        // must converge the buyer's mirrored row (regression: RELEASED was
        // missing from ALLOWED_REMOTE + the forward order, so the buyer
        // stayed "Paid — awaiting seller release" forever).
        assertEquals("RELEASED", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "RELEASED"))
        assertEquals("RELEASED", EscrowRouter.applyRemoteStatus("CONFIRMING", "RELEASED"))
        assertEquals("DISPUTED", EscrowRouter.applyRemoteStatus("CONFIRMING", "DISPUTED"))
    }

    @Test
    fun `signed is a forward state between funded and payment pending`() {
        assertEquals("SIGNED", EscrowRouter.applyRemoteStatus("FUNDED", "SIGNED"))
        assertEquals("PAYMENT_PENDING", EscrowRouter.applyRemoteStatus("SIGNED", "PAYMENT_PENDING"))
        assertEquals("RECEIPT_SENT", EscrowRouter.applyRemoteStatus("SIGNED", "RECEIPT_SENT"))
        assertEquals("CONFIRMING", EscrowRouter.applyRemoteStatus("SIGNED", "CONFIRMING"))
        assertNull(EscrowRouter.applyRemoteStatus("SIGNED", "FUNDED"))
    }

    @Test
    fun `terminal statuses are locked`() {
        assertNull(EscrowRouter.applyRemoteStatus("RELEASED", "FUNDED"))
        assertNull(EscrowRouter.applyRemoteStatus("REFUNDED", "PAYMENT_PENDING"))
        assertNull(EscrowRouter.applyRemoteStatus("CANCELLED", "FUNDING"))
        assertNull(EscrowRouter.applyRemoteStatus("DISPUTED", "CONFIRMING"))
    }

    @Test
    fun `terminal outcomes may land from any non-terminal state`() {
        // The seller's sweep is the authority: auto-cancel / auto-refund /
        // dispute-resolution must converge the buyer's mirrored row even
        // when the buyer is still on an earlier state.
        assertEquals("CANCELLED", EscrowRouter.applyRemoteStatus("FUNDING", "CANCELLED"))
        assertEquals("CANCELLED", EscrowRouter.applyRemoteStatus("FUNDED", "CANCELLED"))
        assertEquals("REFUNDED", EscrowRouter.applyRemoteStatus("FUNDED", "REFUNDED"))
        assertEquals("REFUNDED", EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "REFUNDED"))
    }

    @Test
    fun `funding is not disputable remotely`() {
        // FUNDING is not disputable (2026-09-05): the deposit is either not
        // yet broadcast (nothing to arbitrate) or in flight (unconfirmed —
        // the arbitrator's resolution would spend a nonexistent output). A
        // stale/forged FUNDING dispute from an older build must not flip the
        // mirrored row.
        assertNull(EscrowRouter.applyRemoteStatus("FUNDING", "DISPUTED"))
        // Disputes from funded+ states still open.
        assertEquals("DISPUTED", EscrowRouter.applyRemoteStatus("FUNDED", "DISPUTED"))
        assertEquals("DISPUTED", EscrowRouter.applyRemoteStatus("CONFIRMING", "DISPUTED"))
    }

    @Test
    fun `arbitration outcomes close a disputed escrow`() {
        // After the arbitrator rules, the terminal outcome must land on the
        // counterparty's DISPUTED row — otherwise the buyer stays "In
        // dispute" forever while the seller already broadcast the payout/
        // refund (regression: DISPUTED was in TERMINAL, so the kind:33337
        // terminal publish from storeArbitrationDecision was dropped).
        assertEquals("RELEASED", EscrowRouter.applyRemoteStatus("DISPUTED", "RELEASED"))
        assertEquals("REFUNDED", EscrowRouter.applyRemoteStatus("DISPUTED", "REFUNDED"))
    }

    @Test
    fun `dispute cannot be cancelled remotely`() {
        // CANCELLED is never an arbitration outcome — a DISPUTED row may
        // only close via the arbitrator's RELEASED/REFUNDED.
        assertNull(EscrowRouter.applyRemoteStatus("DISPUTED", "CANCELLED"))
        assertNull(EscrowRouter.applyRemoteStatus("DISPUTED", "FUNDED"))
    }

    @Test
    fun `never downgrades or jumps backwards`() {
        assertNull(EscrowRouter.applyRemoteStatus("RECEIPT_SENT", "PAYMENT_PENDING"))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "FUNDING"))
        assertNull(EscrowRouter.applyRemoteStatus("CONFIRMING", "FUNDED"))
    }

    @Test
    fun `unknown or same status is a no-op`() {
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "FUNDED"))
        assertNull(EscrowRouter.applyRemoteStatus("FUNDED", "BOGUS"))
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
}
