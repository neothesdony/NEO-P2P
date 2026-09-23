package com.neop2p.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationBoundariesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun mig23to24AddsFundedAmountSatsAndPreservesRows() {
        val db = LegacySchema.open(
            context, "mig23.db", 23,
            listOf(
                "CREATE TABLE escrows (" +
                    "escrow_id TEXT NOT NULL PRIMARY KEY, " +
                    "deposit_amount_sats INTEGER NOT NULL)"
            )
        )
        db.execSQL("INSERT INTO escrows (escrow_id, deposit_amount_sats) VALUES ('e1', 1000)")
        LegacySchema.migration(23).migrate(db)
        assertTrue(LegacySchema.columns(db, "escrows").contains("funded_amount_sats"))
        db.query("SELECT deposit_amount_sats FROM escrows WHERE escrow_id='e1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1000L, it.getLong(0))
        }
        db.close()
    }

    @Test
    fun mig24to25AddsLockedAtToTradeOffers() {
        val db = LegacySchema.open(
            context, "mig24.db", 24,
            listOf("CREATE TABLE trade_offers (offer_id TEXT NOT NULL PRIMARY KEY)")
        )
        LegacySchema.migration(24).migrate(db)
        assertTrue(LegacySchema.columns(db, "trade_offers").contains("locked_at"))
        db.close()
    }

    @Test
    fun mig25to26AddsCreatorAndBuyerPubkeys() {
        val db = LegacySchema.open(
            context, "mig25.db", 25,
            listOf("CREATE TABLE trade_offers (offer_id TEXT NOT NULL PRIMARY KEY)")
        )
        LegacySchema.migration(25).migrate(db)
        val cols = LegacySchema.columns(db, "trade_offers")
        assertTrue(cols.contains("creator_pubkey_hex"))
        assertTrue(cols.contains("buyer_pubkey_hex"))
        db.close()
    }

    @Test
    fun mig26to27AddsAttestationAndDisputeColumns() {
        val db = LegacySchema.open(
            context, "mig26.db", 26,
            listOf(
                "CREATE TABLE escrows (escrow_id TEXT NOT NULL PRIMARY KEY)",
                "CREATE TABLE trade_offers (offer_id TEXT NOT NULL PRIMARY KEY)",
                "CREATE TABLE arbitrator_disputes (escrow_id TEXT NOT NULL PRIMARY KEY)"
            )
        )
        LegacySchema.migration(26).migrate(db)
        assertTrue(LegacySchema.columns(db, "escrows").contains("seller_refund_attestation"))
        assertTrue(LegacySchema.columns(db, "escrows").contains("buyer_address_attestation"))
        assertTrue(LegacySchema.columns(db, "trade_offers").contains("buyer_address_attestation"))
        val disputeCols = LegacySchema.columns(db, "arbitrator_disputes")
        listOf(
            "buyer_btc_address", "buyer_pubkey_hex", "seller_pubkey_hex",
            "seller_refund_attestation", "buyer_address_attestation", "offer_id", "trade_sats"
        ).forEach { assertTrue("missing $it", disputeCols.contains(it)) }
        db.close()
    }

    @Test
    fun mig27to28AddsDisputedAt() {
        val db = LegacySchema.open(
            context, "mig27.db", 27,
            listOf("CREATE TABLE escrows (escrow_id TEXT NOT NULL PRIMARY KEY)")
        )
        LegacySchema.migration(27).migrate(db)
        assertTrue(LegacySchema.columns(db, "escrows").contains("disputed_at"))
        db.close()
    }

    @Test
    fun mig28to29DefaultsExistingChatRowsToPending() {
        val db = LegacySchema.open(
            context, "mig28.db", 28,
            listOf("CREATE TABLE chat_messages (message_id TEXT NOT NULL PRIMARY KEY)")
        )
        db.execSQL("INSERT INTO chat_messages (message_id) VALUES ('c1')")
        LegacySchema.migration(28).migrate(db)
        db.query("SELECT delivery_status FROM chat_messages WHERE message_id='c1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("pending", it.getString(0))
        }
        db.close()
    }

    @Test
    fun mig29to30DropsArbitratorDisputes() {
        val db = LegacySchema.open(
            context, "mig29.db", 29,
            listOf("CREATE TABLE arbitrator_disputes (escrow_id TEXT NOT NULL PRIMARY KEY)")
        )
        LegacySchema.migration(29).migrate(db)
        assertFalse(LegacySchema.tableExists(db, "arbitrator_disputes"))
        db.close()
    }

    @Test
    fun mig30to31PinsRnsIdentityHashOnConversationKeys() {
        val db = LegacySchema.open(
            context, "mig30.db", 30,
            listOf("CREATE TABLE conversation_keys (peerId TEXT NOT NULL PRIMARY KEY)")
        )
        LegacySchema.migration(30).migrate(db)
        assertTrue(LegacySchema.columns(db, "conversation_keys").contains("rns_identity_hash"))
        db.close()
    }

    @Test
    fun mig31to32AddsTheCltvTemplateColumns() {
        val db = LegacySchema.open(
            context, "mig31.db", 31,
            listOf("CREATE TABLE escrows (escrow_id TEXT NOT NULL PRIMARY KEY)")
        )
        LegacySchema.migration(31).migrate(db)
        val cols = LegacySchema.columns(db, "escrows")
        assertTrue(cols.contains("script_template"))
        assertTrue(cols.contains("cltv_locktime"))
        db.close()
    }

    @Test
    fun mig32to33AddsRatchetColumnsAndDropsLegacySessions() {
        val db = LegacySchema.open(
            context, "mig32.db", 32,
            listOf(
                "CREATE TABLE conversation_keys (" +
                    "peerId TEXT NOT NULL PRIMARY KEY, " +
                    "theirPublicKey BLOB NOT NULL, " +
                    "created_at INTEGER NOT NULL)",
                "CREATE TABLE chat_messages (message_id TEXT NOT NULL PRIMARY KEY)"
            )
        )
        db.execSQL(
            "INSERT INTO conversation_keys (peerId, theirPublicKey, created_at) " +
                "VALUES ('p1', X'00', 1)"
        )
        LegacySchema.migration(32).migrate(db)
        val keyCols = LegacySchema.columns(db, "conversation_keys")
        listOf(
            "protocol_version", "ratchet_state", "spk_priv", "spk_pub",
            "their_ik_pub", "identity_pub_ed", "peer_ratchet_pub"
        ).forEach { assertTrue("missing $it", keyCols.contains(it)) }
        assertTrue(LegacySchema.columns(db, "chat_messages").contains("plaintext"))
        // The hard fork deletes legacy v1 sessions.
        db.query("SELECT COUNT(*) FROM conversation_keys").use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
        db.close()
    }

    @Test
    fun forwardChain23to33AppliesInOrderWithoutError() {
        val db = LegacySchema.open(
            context, "migchain.db", 23,
            listOf(
                "CREATE TABLE escrows (escrow_id TEXT NOT NULL PRIMARY KEY, " +
                    "deposit_amount_sats INTEGER NOT NULL)",
                "CREATE TABLE trade_offers (offer_id TEXT NOT NULL PRIMARY KEY)",
                "CREATE TABLE conversation_keys (peerId TEXT NOT NULL PRIMARY KEY)",
                "CREATE TABLE chat_messages (message_id TEXT NOT NULL PRIMARY KEY)",
                "CREATE TABLE arbitrator_disputes (escrow_id TEXT NOT NULL PRIMARY KEY)"
            )
        )
        (23..32).forEach { from -> LegacySchema.migration(from).migrate(db) }
        assertTrue(LegacySchema.columns(db, "escrows").contains("cltv_locktime"))
        assertTrue(LegacySchema.columns(db, "conversation_keys").contains("peer_ratchet_pub"))
        assertFalse(LegacySchema.tableExists(db, "arbitrator_disputes"))
        db.close()
    }
}
