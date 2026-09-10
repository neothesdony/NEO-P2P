package com.neop2p.ui.screens.escrow

import com.neop2p.R
import org.junit.Assert.assertEquals
import org.junit.Test

class FundingWindowCopyTest {

    /**
     * The seller's device runs the 60s sweep: at window end it checks for a
     * deposit and either cancels or promotes. Its expired copy must say
     * "checking", never a terminal claim.
     */
    @Test
    fun `seller device shows checking copy at window end`() {
        assertEquals(R.string.escrow_funding_window_checking, fundingWindowExpiredKey(isSweepAuthority = true))
    }

    /**
     * The buyer's device has NO sweep authority — cancellation arrives as an
     * LXMF event. Its expired copy must wait for the seller, not claim
     * cancellation on the local clock.
     */
    @Test
    fun `buyer device shows waiting-for-seller copy at window end`() {
        assertEquals(R.string.escrow_funding_window_syncing, fundingWindowExpiredKey(isSweepAuthority = false))
    }

    /**
     * The buyer's mirrored row learns the funding txid via the same-status
     * escrow_status refresh (EscrowRouter.kt:239-246). Once it exists the
     * waiting card must say "broadcast, awaiting confirmation" — the header
     * chip already flips, the card body used to stay static.
     */
    @Test
    fun `buyer card shows broadcast copy once a funding txid exists`() {
        assertEquals(
            R.string.escrow_funding_broadcast_buyer_body,
            fundingWaitBodyKey(hasFundingTxId = true)
        )
    }

    @Test
    fun `buyer card keeps waiting copy before any broadcast`() {
        assertEquals(
            R.string.escrow_funding_wait_buyer_body,
            fundingWaitBodyKey(hasFundingTxId = false)
        )
    }

    /**
     * Once the funding txid exists the FUNDING next-action must say the
     * deposit is broadcast and awaiting confirmation — the old "send BTC"
     * copy contradicts the UI right below it (fund button disabled,
     * txid field filled, Verify is the real next action).
     */
    @Test
    fun `next action says broadcast once a funding txid exists`() {
        assertEquals(
            R.string.next_action_funding_broadcast,
            fundingNextActionKey(hasFundingTxId = true, isSeller = true)
        )
        assertEquals(
            R.string.next_action_funding_broadcast,
            fundingNextActionKey(hasFundingTxId = true, isSeller = false)
        )
    }

    @Test
    fun `next action keeps fund copy before any broadcast`() {
        assertEquals(
            R.string.next_action_funding_seller,
            fundingNextActionKey(hasFundingTxId = false, isSeller = true)
        )
        assertEquals(
            R.string.next_action_funding_buyer,
            fundingNextActionKey(hasFundingTxId = false, isSeller = false)
        )
    }
}
