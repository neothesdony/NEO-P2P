package com.neop2p.data.escrow

import com.neop2p.domain.model.EscrowStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowCancelDecisionTest {

    private val txid = "1111111111111111111111111111111111111111111111111111111111111111"

    @Test
    fun `funding with no txid and no deposit cancels locally`() {
        assertFalse(EscrowService.cancelRequiresOnChainRefund(EscrowStatus.FUNDING, null, null))
        assertFalse(EscrowService.cancelRequiresOnChainRefund(EscrowStatus.FUNDING, "", 0L))
    }

    @Test
    fun `funding with a bound txid refunds on chain`() {
        assertTrue(EscrowService.cancelRequiresOnChainRefund(EscrowStatus.FUNDING, txid, null))
    }

    @Test
    fun `funding with a partial deposit refunds on chain`() {
        assertTrue(EscrowService.cancelRequiresOnChainRefund(EscrowStatus.FUNDING, txid, 50_000L))
    }

    @Test
    fun `funded disputed and confirming always refund on chain`() {
        assertTrue(EscrowService.cancelRequiresOnChainRefund(EscrowStatus.FUNDED, null, null))
        assertTrue(EscrowService.cancelRequiresOnChainRefund(EscrowStatus.DISPUTED, null, null))
        assertTrue(EscrowService.cancelRequiresOnChainRefund(EscrowStatus.CONFIRMING, null, null))
    }
}
