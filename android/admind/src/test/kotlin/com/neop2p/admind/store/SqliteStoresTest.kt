package com.neop2p.admind.store

import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.data.p2p.EvidenceRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/**
 * SQLite-JDBC store round-trips. The daemon's durability must match what the
 * app's Room twin would persist for the same canonical records.
 */
class SqliteStoresTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dbPath(): Path = tmp.newFolder().resolve("disputes.db").toPath()

    private fun dispute(
        escrowId: String = "esc-1",
        openedBy: String = "buyer",
        openedAt: Long = 100L,
        resolved: Boolean = false,
        withF2: Boolean = true,
    ) = DisputeRecord(
        escrowId = escrowId,
        openedBy = openedBy,
        reason = "not paid",
        openedAt = openedAt,
        redeemScriptHex = "aa",
        psbtHex = "bb",
        refundTxHex = "cc",
        depositSats = 4321L,
        fundingScriptType = "P2WSH",
        sellerRefundAddress = "bc1qrefund",
        buyerPeerId = "buyer",
        sellerPeerId = "seller",
        buyerBtcAddress = if (withF2) "bc1qbuyer" else null,
        buyerPubkeyHex = if (withF2) "02aa" else null,
        sellerPubkeyHex = if (withF2) "02bb" else null,
        sellerRefundAttestation = if (withF2) "att-seller" else null,
        buyerAddressAttestation = if (withF2) "att-buyer" else null,
        offerId = if (withF2) "off-1" else null,
        tradeSats = if (withF2) 9000L else null,
        receivedAt = 200L,
        resolved = resolved,
    )

    private fun evidence(
        evidenceId: String = "ev-1",
        escrowId: String = "esc-1",
        submittedAt: Long = 1L,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ) = EvidenceRecord(
        evidenceId = evidenceId,
        escrowId = escrowId,
        submitterPeerId = "buyer",
        description = "receipt",
        mimeType = "image/jpeg",
        imageData = bytes,
        submittedAt = submittedAt,
    )

    @Test
    fun `dispute round-trips every column`() {
        val path = dbPath()
        val store = SqliteDisputeStore(path)
        store.upsert(dispute())

        val loaded = store.getById("esc-1")!!
        assertEquals("esc-1", loaded.escrowId)
        assertEquals("buyer", loaded.openedBy)
        assertEquals("not paid", loaded.reason)
        assertEquals(100L, loaded.openedAt)
        assertEquals("aa", loaded.redeemScriptHex)
        assertEquals("bb", loaded.psbtHex)
        assertEquals("cc", loaded.refundTxHex)
        assertEquals(4321L, loaded.depositSats)
        assertEquals("P2WSH", loaded.fundingScriptType)
        assertEquals("bc1qrefund", loaded.sellerRefundAddress)
        assertEquals("buyer", loaded.buyerPeerId)
        assertEquals("seller", loaded.sellerPeerId)
        assertEquals("bc1qbuyer", loaded.buyerBtcAddress)
        assertEquals("02aa", loaded.buyerPubkeyHex)
        assertEquals("02bb", loaded.sellerPubkeyHex)
        assertEquals("att-seller", loaded.sellerRefundAttestation)
        assertEquals("att-buyer", loaded.buyerAddressAttestation)
        assertEquals("off-1", loaded.offerId)
        assertEquals(9000L, loaded.tradeSats)
        assertEquals(200L, loaded.receivedAt)
        assertFalse(loaded.resolved)
    }

    @Test
    fun `dispute round-trips null columns`() {
        val path = dbPath()
        val store = SqliteDisputeStore(path)
        store.upsert(dispute(withF2 = false))

        val loaded = store.getById("esc-1")!!
        assertNull(loaded.buyerBtcAddress)
        assertNull(loaded.buyerPubkeyHex)
        assertNull(loaded.sellerPubkeyHex)
        assertNull(loaded.sellerRefundAttestation)
        assertNull(loaded.buyerAddressAttestation)
        assertNull(loaded.offerId)
        assertNull(loaded.tradeSats)
    }

    @Test
    fun `dispute upsert replaces the existing row`() {
        val store = SqliteDisputeStore(dbPath())
        store.upsert(dispute())
        store.upsert(dispute().copy(reason = "changed", resolved = true))

        val loaded = store.getById("esc-1")!!
        assertEquals("changed", loaded.reason)
        assertTrue(loaded.resolved)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `countUnresolvedBySender counts only unresolved rows from that sender`() {
        val store = SqliteDisputeStore(dbPath())
        store.upsert(dispute(escrowId = "a", openedBy = "buyer"))
        store.upsert(dispute(escrowId = "b", openedBy = "buyer"))
        store.upsert(dispute(escrowId = "c", openedBy = "seller"))
        store.markResolved("b")

        assertEquals(1, store.countUnresolvedBySender("buyer"))
        assertEquals(1, store.countUnresolvedBySender("seller"))
        assertEquals(0, store.countUnresolvedBySender("nobody"))
    }

    @Test
    fun `all orders by opened_at descending`() {
        val store = SqliteDisputeStore(dbPath())
        store.upsert(dispute(escrowId = "old", openedAt = 1L))
        store.upsert(dispute(escrowId = "new", openedAt = 3L))
        store.upsert(dispute(escrowId = "mid", openedAt = 2L))

        assertEquals(listOf("new", "mid", "old"), store.all().map { it.escrowId })
    }

    @Test
    fun `clear empties the dispute table`() {
        val store = SqliteDisputeStore(dbPath())
        store.upsert(dispute())
        store.clear()
        assertEquals(0, store.all().size)
    }

    @Test
    fun `evidence round-trips bytes and metadata`() {
        val store = SqliteEvidenceStore(dbPath())
        val bytes = ByteArray(256) { it.toByte() }
        store.insert(evidence(bytes = bytes))

        val loaded = store.forEscrow("esc-1").single()
        assertEquals("ev-1", loaded.evidenceId)
        assertEquals("buyer", loaded.submitterPeerId)
        assertEquals("receipt", loaded.description)
        assertEquals("image/jpeg", loaded.mimeType)
        assertEquals(1L, loaded.submittedAt)
        assertArrayEquals(bytes, loaded.imageData)
    }

    @Test
    fun `evidence forEscrow is ordered by submitted_at ascending`() {
        val store = SqliteEvidenceStore(dbPath())
        store.insert(evidence(evidenceId = "third", submittedAt = 3L))
        store.insert(evidence(evidenceId = "first", submittedAt = 1L))
        store.insert(evidence(evidenceId = "second", submittedAt = 2L))

        assertEquals(listOf("first", "second", "third"), store.forEscrow("esc-1").map { it.evidenceId })
        assertEquals(3, store.all().size)
    }

    @Test
    fun `evidence insert replaces on the same id`() {
        val store = SqliteEvidenceStore(dbPath())
        store.insert(evidence(bytes = byteArrayOf(1)))
        store.insert(evidence(bytes = byteArrayOf(2)))

        assertEquals(1, store.forEscrow("esc-1").size)
        assertArrayEquals(byteArrayOf(2), store.forEscrow("esc-1").single().imageData)
    }

    @Test
    fun `clear empties the evidence table`() {
        val store = SqliteEvidenceStore(dbPath())
        store.insert(evidence())
        store.clear()
        assertEquals(0, store.all().size)
    }

    @Test
    fun `a second store instance over the same path sees persisted rows`() {
        val path = tmp.newFolder().resolve("shared.db").toPath()
        SqliteDisputeStore(path).upsert(dispute())
        SqliteEvidenceStore(path).insert(evidence())

        assertEquals(1, SqliteDisputeStore(path).all().size)
        assertEquals(1, SqliteEvidenceStore(path).all().size)
    }
}
