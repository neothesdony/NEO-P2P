package com.neop2p.data.escrow

import com.neop2p.data.escrow.ChainMonitor.TxInfo
import com.neop2p.data.escrow.ChainMonitor.TxOutput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowBuyerFundingCheckTest {

    private val addr = "tb1qescrow"
    private fun out(value: Long, index: Int = 0) = TxOutput(addr, value, index)
    private fun info(confirmed: Boolean = true, confs: Long = 1, blockTime: Long = 2_000_000_000L) =
        TxInfo("txid", confirmed, confs, blockTime)

    @Test fun `a sufficient confirmed deposit passes`() {
        val r = EscrowService.checkBuyerFunding(
            "txid", addr, 100_000L, listOf(out(100_000L)), info(), 1, trustedAnchorMs = 1_000_000_000_000L
        )
        assertTrue(r.ok); assertTrue(r.fundedValueSats == 100_000L)
    }

    @Test fun `an underpaid or missing deposit fails`() {
        assertFalse(EscrowService.checkBuyerFunding("txid", addr, 100_000L, listOf(out(50_000L)), info(), 1, null).ok)
        assertFalse(EscrowService.checkBuyerFunding("txid", addr, 100_000L, emptyList(), info(), 1, null).ok)
        assertFalse(EscrowService.checkBuyerFunding(null, addr, 100_000L, listOf(out(100_000L)), info(), 1, null).ok)
    }

    @Test fun `an unconfirmed or shallow deposit fails`() {
        assertFalse(EscrowService.checkBuyerFunding("txid", addr, 100_000L, listOf(out(100_000L)), info(confirmed = false, confs = 0), 1, null).ok)
        assertFalse(EscrowService.checkBuyerFunding("txid", addr, 100_000L, listOf(out(100_000L)), info(confs = 0), 1, null).ok)
    }

    @Test fun `an unavailable tx info fails closed`() {
        assertFalse(EscrowService.checkBuyerFunding("txid", addr, 100_000L, listOf(out(100_000L)), null, 1, null).ok)
    }

    @Test fun `a deposit mined before the local match is stale and fails`() {
        val r = EscrowService.checkBuyerFunding(
            "txid", addr, 100_000L, listOf(out(100_000L)),
            info(blockTime = 1_000L), 1, trustedAnchorMs = 2_000_000_000_000L
        )
        assertFalse(r.ok)
    }
}
