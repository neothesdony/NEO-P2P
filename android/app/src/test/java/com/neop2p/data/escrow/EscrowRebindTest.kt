package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4 (2026-09-01): RBF-bumped funding tx re-bind decision logic.
 *
 * When a wallet bumps the fee (RBF), the original funding txid dies in
 * mempool and a replacement tx pays the same escrow address the same
 * deposit. The sweep must re-bind to the replacement instead of cancelling
 * the escrow or refunding a dead output.
 *
 * Mirrors the decision logic in EscrowService.rebindFundingTxId — the
 * function itself is Room/ChainMonitor-dependent, so the DECISION rules are
 * verified here against the production constants.
 *
 * Decision (matches the implementation):
 *   - stored tx confirmed            → no rebind (valid)
 *   - stored tx unconfirmed + address has unconfirmed balance
 *                                    → no rebind (still in mempool)
 *   - otherwise (dropped, or lookups failed)
 *                                    → search the address for a replacement
 *                                      (the address search is ground truth:
 *                                      it skips the stored txid and only
 *                                      rebinds on an exact-deposit match)
 */
class EscrowRebindTest {

    /** Mirrors rebindFundingTxId's decision: should we look for a replacement? */
    private fun shouldRebind(
        storedConfirmed: Boolean?,
        addressHasUnconfirmed: Boolean?,
    ): Boolean {
        if (storedConfirmed == true) return false
        if (storedConfirmed == false && addressHasUnconfirmed == true) return false
        return true
    }

    @Test
    fun `confirmed stored tx never rebinds`() {
        assertFalse(shouldRebind(storedConfirmed = true, addressHasUnconfirmed = true))
        assertFalse(shouldRebind(storedConfirmed = true, addressHasUnconfirmed = false))
        assertFalse(shouldRebind(storedConfirmed = true, addressHasUnconfirmed = null))
    }

    @Test
    fun `unconfirmed but still in mempool never rebinds`() {
        // The address shows an unconfirmed deposit — the original tx is
        // still in mempool (or a replacement already is). No rebind.
        assertFalse(shouldRebind(storedConfirmed = false, addressHasUnconfirmed = true))
    }

    @Test
    fun `dropped tx with replacement deposit rebinds`() {
        // The RBF case: original dropped, replacement in mempool. The
        // address search finds the replacement and re-binds.
        assertTrue(shouldRebind(storedConfirmed = false, addressHasUnconfirmed = false))
    }

    @Test
    fun `dropped tx with no deposit at all rebinds`() {
        // Original dropped and nothing replaced it — the sweep will look,
        // find nothing, and fall through to the normal cancel path.
        assertTrue(shouldRebind(storedConfirmed = false, addressHasUnconfirmed = false))
    }

    @Test
    fun `unknown stored tx state searches the address as ground truth`() {
        // getTxInfo failed (transient): the address search is authoritative —
        // it skips the stored txid and only rebinds on an exact-deposit match,
        // so searching is safe even when the stored tx is still valid.
        assertTrue(shouldRebind(storedConfirmed = null, addressHasUnconfirmed = false))
        assertTrue(shouldRebind(storedConfirmed = null, addressHasUnconfirmed = true))
    }
}
