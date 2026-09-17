package com.neop2p.data.wallet

import com.neop2p.data.escrow.ChainMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSnapshotStoreTest {

    private fun tx(txid: String, direction: ChainMonitor.TxDirection) = ChainMonitor.AddressTx(
        txid = txid,
        confirmed = true,
        blockTimeSec = 1_700_000_000L,
        feeSats = 250L,
        receivedSats = 10_000L,
        spentSats = 0L,
        netSats = 10_000L,
        direction = direction
    )

    @Test
    fun `round trips balance and history`() {
        val snapshot = WalletSnapshotStore.Snapshot(
            confirmedSats = 4_991_100L,
            unconfirmedSats = 1_234L,
            txs = listOf(
                tx("aa", ChainMonitor.TxDirection.RECEIVE),
                tx("bb", ChainMonitor.TxDirection.SEND)
            )
        )
        val parsed = WalletSnapshotStore.parse(WalletSnapshotStore.toJson(snapshot))
        assertEquals(snapshot, parsed)
    }

    @Test
    fun `empty history round trips`() {
        val snapshot = WalletSnapshotStore.Snapshot(0L, 0L, emptyList())
        assertEquals(snapshot, WalletSnapshotStore.parse(WalletSnapshotStore.toJson(snapshot)))
    }

    @Test
    fun `a snapshot from another identity is rejected`() {
        val json = WalletSnapshotStore.toJson(
            WalletSnapshotStore.Snapshot(1L, 0L, emptyList()),
            identity = "peerA"
        )
        assertNull(WalletSnapshotStore.parse(json, identity = "peerB"))
        assertTrue(WalletSnapshotStore.parse(json, identity = "peerA") != null)
    }

    @Test
    fun `malformed and blank blobs parse to null`() {
        assertNull(WalletSnapshotStore.parse(null))
        assertNull(WalletSnapshotStore.parse(""))
        assertNull(WalletSnapshotStore.parse("{not json"))
    }
}
