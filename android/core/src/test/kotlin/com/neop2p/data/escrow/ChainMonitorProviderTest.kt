package com.neop2p.data.escrow

import com.neop2p.data.network.Capability
import com.neop2p.data.network.ExplorerProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Facade behavior over fake providers: capability filtering, sticky preference,
 * and fail-closed semantics. No network.
 */
class ChainMonitorProviderTest {

    private class FakeProvider(
        override val id: String,
        override val capabilities: Set<Capability>,
    ) : ExplorerProvider {
        var txInfoResult: ChainMonitor.TxInfo? = null
        var txOutputsResult: List<ChainMonitor.TxOutput>? = null
        var feeResult: ChainMonitor.FeeEstimate? = null
        var tipResult: Long? = null
        var broadcastResult: String? = null
        var txInfoCallCount = 0
        var broadcastCallCount = 0

        override suspend fun txInfo(txid: String, tipHeight: Long?): ChainMonitor.TxInfo? {
            txInfoCallCount++
            return txInfoResult
        }

        override suspend fun txOutputs(txid: String): List<ChainMonitor.TxOutput>? = txOutputsResult

        override suspend fun feeEstimate(): ChainMonitor.FeeEstimate? = feeResult

        override suspend fun tipHeight(): Long? = tipResult

        override suspend fun broadcast(txHex: String, expectedTxid: String?): String? {
            broadcastCallCount++
            return broadcastResult
        }
    }

    @Test
    fun `capability filtering skips providers that do not support the operation`() = runTest {
        val feesOnly = FakeProvider("fees-only", setOf(Capability.FEES))
        val info = FakeProvider("info", setOf(Capability.TX_INFO))
        info.txInfoResult = ChainMonitor.TxInfo("tx1", true, 3, 0)
        val monitor = ChainMonitor(listOf(feesOnly, info))

        val result = monitor.getTxInfo("tx1")

        assertTrue(result.isSuccess)
        assertEquals("tx1", result.getOrNull()!!.txid)
        assertEquals(0, feesOnly.txInfoCallCount)
    }

    @Test
    fun `sticky preference retries the last good provider first`() = runTest {
        val a = FakeProvider("a", setOf(Capability.TX_INFO))
        val b = FakeProvider("b", setOf(Capability.TX_INFO))
        b.txInfoResult = ChainMonitor.TxInfo("tx1", true, 3, 0)
        val monitor = ChainMonitor(listOf(a, b))

        monitor.getTxInfo("tx1")
        assertEquals(1, a.txInfoCallCount)
        monitor.getTxInfo("tx1")

        // b answered first, so a is no longer consulted on the second call.
        assertEquals(1, a.txInfoCallCount)
        assertEquals(2, b.txInfoCallCount)
    }

    @Test
    fun `all providers failing is a failure`() = runTest {
        val monitor = ChainMonitor(
            listOf(
                FakeProvider("a", setOf(Capability.TX_INFO)),
                FakeProvider("b", setOf(Capability.TX_INFO)),
            )
        )
        assertTrue(monitor.getTxInfo("tx1").isFailure)
    }

    @Test
    fun `no capable provider is a failure`() = runTest {
        val monitor = ChainMonitor(listOf(FakeProvider("a", setOf(Capability.FEES))))
        assertTrue(monitor.getTxOutputs("tx1").isFailure)
        assertTrue(monitor.getAddressInfo("addr").isFailure)
        assertTrue(monitor.getAddressTxs("addr").isFailure)
        assertTrue(monitor.getAddressUtxos("addr").isFailure)
    }

    @Test
    fun `broadcast rejects a txid that is not the expected one`() = runTest {
        val expected = "a".repeat(64)
        val p = FakeProvider("a", setOf(Capability.BROADCAST))
        p.broadcastResult = "b".repeat(64)
        val monitor = ChainMonitor(listOf(p))
        assertTrue(monitor.broadcastTx("00", expected).isFailure)
    }

    @Test
    fun `broadcast accepts the exact expected txid`() = runTest {
        val expected = "a".repeat(64)
        val p = FakeProvider("a", setOf(Capability.BROADCAST))
        p.broadcastResult = expected
        val monitor = ChainMonitor(listOf(p))
        assertEquals(expected, monitor.broadcastTx("00", expected).getOrNull())
    }

    @Test
    fun `fee estimate falls back to defaults when no provider answers`() = runTest {
        val fallback = ChainMonitor(listOf(FakeProvider("a", setOf(Capability.FEES)))).estimateFees()
        assertEquals(50L, fallback.fastest)
        assertEquals(30L, fallback.halfHour)
        assertEquals(20L, fallback.hour)
    }

    @Test
    fun `tip height is null when no provider answers`() = runTest {
        assertNull(ChainMonitor(listOf(FakeProvider("a", setOf(Capability.TIP)))).tipHeight())
    }

    @Test
    fun `empty outputs are treated as no answer and the next provider wins`() = runTest {
        val empty = FakeProvider("empty", setOf(Capability.TX_OUTPUTS))
        empty.txOutputsResult = emptyList()
        val good = FakeProvider("good", setOf(Capability.TX_OUTPUTS))
        good.txOutputsResult = listOf(ChainMonitor.TxOutput("escrow", 804596L, 0))

        val result = ChainMonitor(listOf(empty, good)).getTxOutputs("tx1")

        assertTrue(result.isSuccess)
        assertEquals(804596L, result.getOrNull()!!.single().valueSats)
    }

    @Test
    fun `all providers returning empty outputs is a failure`() = runTest {
        val a = FakeProvider("a", setOf(Capability.TX_OUTPUTS)).apply { txOutputsResult = emptyList() }
        val b = FakeProvider("b", setOf(Capability.TX_OUTPUTS)).apply { txOutputsResult = emptyList() }

        assertTrue(ChainMonitor(listOf(a, b)).getTxOutputs("tx1").isFailure)
    }
}
