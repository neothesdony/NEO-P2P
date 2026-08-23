package com.neop2p.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.neop2p.data.local.dao.PeerDao
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.local.dao.ConversationKeyDao
import com.neop2p.data.local.dao.PendingMessageDao
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.entity.PeerEntity
import com.neop2p.data.local.entity.TradeOfferEntity
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.local.entity.ConversationKeyEntity
import com.neop2p.data.local.entity.PendingMessageEntity
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.data.local.entity.DisputeEvidenceEntity

@Database(
    entities = [
        PeerEntity::class,
        TradeOfferEntity::class,
        ChatMessageEntity::class,
        ConversationKeyEntity::class,
        PendingMessageEntity::class,
        EscrowEntity::class,
        DisputeEvidenceEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun offerDao(): OfferDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun conversationKeyDao(): ConversationKeyDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun escrowDao(): EscrowDao
    abstract fun disputeEvidenceDao(): DisputeEvidenceDao

    companion object {
        private const val DB_NAME = "neop2p.db"

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_messages (" +
                        "message_id TEXT NOT NULL PRIMARY KEY, " +
                        "to_peer_id TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "payload BLOB NOT NULL, " +
                        "created_at INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE escrows ADD COLUMN redeem_script_hex TEXT")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // E2EE v2 (P0-2): replaced the archived libsignal-protocol-java
                // (protobuf-javalite classes that crashed under protobuf-java) with
                // NIP-44-style ECDH+XChaCha20. Old Signal store tables are dropped;
                // chat history blobs (stored ciphertext) are unrecoverable either way
                // (the old sessions were broken on disk), so no data is preserved.
                db.execSQL("DROP TABLE IF EXISTS signal_pre_keys")
                db.execSQL("DROP TABLE IF EXISTS signal_sessions")
                db.execSQL("DROP TABLE IF EXISTS signal_signed_pre_keys")
                db.execSQL("DROP TABLE IF EXISTS signal_identity_keys")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversation_keys (" +
                        "peerId TEXT NOT NULL PRIMARY KEY, " +
                        "theirPublicKey BLOB NOT NULL, " +
                        "created_at INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // FIX 5: 2-of-3 multisig escrow was dead code (never reachable).
                // Drop its tables so the schema matches the removed entities/DAOs.
                db.execSQL("DROP TABLE IF EXISTS escrows")
                db.execSQL("DROP TABLE IF EXISTS dispute_evidence")
            }
        }

        /**
         * Re-introduce the on-chain 2-of-3 multisig escrow subsystem (P0-0).
         * Columns MUST match the @Entity definitions exactly (Room schema validation).
         * Includes P0-1 hardening: buyer_pubkey_hex / seller_pubkey_hex.
         */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS escrows (" +
                        "escrow_id TEXT NOT NULL PRIMARY KEY, " +
                        "offer_id TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "funding_tx_id TEXT, " +
                        "payout_tx_id TEXT, " +
                        "funding_address TEXT, " +
                        "funding_address_path TEXT, " +
                        "redeem_script_hex TEXT, " +
                        "psbt_unsigned BLOB, " +
                        "psbt_buyer_signed BLOB, " +
                        "deposit_amount_sats INTEGER NOT NULL, " +
                        "trade_amount_sats INTEGER NOT NULL, " +
                        "fee_amount_sats INTEGER NOT NULL, " +
                        "fee_address TEXT NOT NULL, " +
                        "buyer_peer_id TEXT NOT NULL, " +
                        "seller_peer_id TEXT NOT NULL, " +
                        "buyer_pubkey_hex TEXT, " +
                        "seller_pubkey_hex TEXT, " +
                        "status TEXT NOT NULL, " +
                        "buyer_signature BLOB, " +
                        "seller_signature BLOB, " +
                        "arbitrator_signature BLOB, " +
                        "arbitrator_decision TEXT, " +
                        "arbitrator_notes TEXT, " +
                        "channel_point TEXT, " +
                        "created_at INTEGER NOT NULL, " +
                        "released_at INTEGER)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS dispute_evidence (" +
                        "evidence_id TEXT NOT NULL PRIMARY KEY, " +
                        "escrow_id TEXT NOT NULL, " +
                        "submitter_peer_id TEXT NOT NULL, " +
                        "description TEXT NOT NULL, " +
                        "mime_type TEXT NOT NULL, " +
                        "image_data BLOB NOT NULL, " +
                        "submitted_at INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Add the `funded_at` column to escrows (v10 → v11).
         *
         * Records when the funding tx was confirmed on-chain so the 15-minute
         * auto-refund timeout is measured from confirmation (status FUNDED),
         * not from escrow creation. Backfilled with created_at so pre-migration
         * FUNDED escrows still expire correctly.
         */
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE escrows ADD COLUMN funded_at INTEGER")
                db.execSQL("UPDATE escrows SET funded_at = created_at WHERE funded_at IS NULL")
            }
        }

        /**
         * Add the `network_fee_sats` column to escrows (v11 → v12).
         *
         * Network/miner fee the payout tx pays on-chain, estimated from the
         * fastest fee rate × payout vsize at escrow creation. Backfilled with 0
         * for existing rows (old escrows simply have no recorded network fee).
         */
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE escrows ADD COLUMN network_fee_sats INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: runBlocking(Dispatchers.IO) {
                    // The sqlcipher-android artifact does NOT auto-load its native
                    // library. Load it explicitly before opening the encrypted DB.
                    System.loadLibrary("sqlcipher")
                    val passphrase = SqlCipherPassphraseManager.getPassphrase(context)
                    val factory = SupportOpenHelperFactory(passphrase)
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DB_NAME
                    )
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build()
                    .also { INSTANCE = it }
                }
            }
        }
    }
}
