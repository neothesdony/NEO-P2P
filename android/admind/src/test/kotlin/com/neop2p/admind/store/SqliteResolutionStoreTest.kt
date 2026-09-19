package com.neop2p.admind.store

import com.neop2p.data.p2p.PendingResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/**
 * Round-trip coverage for the daemon's durable pending-resolution queue. The
 * row must match what the app's SharedPreferences twin persists for the same
 * canonical [PendingResolution].
 */
class SqliteResolutionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dbPath(): Path = tmp.newFolder().resolve("resolutions.db").toPath()

    private fun pending(
        escrowId: String = "esc-1",
        targets: List<String> = listOf("buyer", "seller"),
        notes: String? = "paid",
        sellerRefundAddress: String? = "bc1qrefund",
        signedTxHex: String? = "txhex",
    ) = PendingResolution(
        escrowId = escrowId,
        decision = "RELEASE_TO_BUYER",
        arbitratorSigHex = "deadbeef",
        notes = notes,
        sellerRefundAddress = sellerRefundAddress,
        signedTxHex = signedTxHex,
        targets = targets,
    )

    @Test
    fun `resolution round-trips every field`() {
        val store = SqliteResolutionStore(dbPath())
        store.save(pending())

        val loaded = store.load("esc-1")!!
        assertEquals("esc-1", loaded.escrowId)
        assertEquals("RELEASE_TO_BUYER", loaded.decision)
        assertEquals("deadbeef", loaded.arbitratorSigHex)
        assertEquals("paid", loaded.notes)
        assertEquals("bc1qrefund", loaded.sellerRefundAddress)
        assertEquals("txhex", loaded.signedTxHex)
        assertEquals(listOf("buyer", "seller"), loaded.targets)
    }

    @Test
    fun `resolution round-trips null optionals`() {
        val store = SqliteResolutionStore(dbPath())
        store.save(pending(notes = null, sellerRefundAddress = null, signedTxHex = null))

        val loaded = store.load("esc-1")!!
        assertNull(loaded.notes)
        assertNull(loaded.sellerRefundAddress)
        assertNull(loaded.signedTxHex)
    }

    @Test
    fun `resolution round-trips empty and single targets`() {
        val store = SqliteResolutionStore(dbPath())
        store.save(pending(escrowId = "none", targets = emptyList()))
        store.save(pending(escrowId = "one", targets = listOf("seller")))

        assertEquals(emptyList<String>(), store.load("none")!!.targets)
        assertEquals(listOf("seller"), store.load("one")!!.targets)
    }

    @Test
    fun `save replaces the row for the same escrow`() {
        val store = SqliteResolutionStore(dbPath())
        store.save(pending(targets = listOf("buyer", "seller")))
        store.save(pending(targets = listOf("seller")))

        assertEquals(1, store.all().size)
        assertEquals(listOf("seller"), store.load("esc-1")!!.targets)
    }

    @Test
    fun `all is ordered by escrow id`() {
        val store = SqliteResolutionStore(dbPath())
        store.save(pending(escrowId = "c"))
        store.save(pending(escrowId = "a"))
        store.save(pending(escrowId = "b"))

        assertEquals(listOf("a", "b", "c"), store.all().map { it.escrowId })
    }

    @Test
    fun `remove deletes one row`() {
        val store = SqliteResolutionStore(dbPath())
        store.save(pending(escrowId = "a"))
        store.save(pending(escrowId = "b"))
        store.remove("a")

        assertNull(store.load("a"))
        assertEquals(listOf("b"), store.all().map { it.escrowId })
    }

    @Test
    fun `clear empties the table`() {
        val store = SqliteResolutionStore(dbPath())
        store.save(pending())
        store.clear()
        assertEquals(0, store.all().size)
    }

    @Test
    fun `a second store instance over the same path sees persisted rows`() {
        val path = tmp.newFolder().resolve("shared.db").toPath()
        SqliteResolutionStore(path).save(pending())

        assertEquals(listOf("esc-1"), SqliteResolutionStore(path).all().map { it.escrowId })
    }
}
