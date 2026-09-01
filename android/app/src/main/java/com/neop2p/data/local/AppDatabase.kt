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
import com.neop2p.data.local.dao.ArbitratorDisputeDao
import com.neop2p.data.local.dao.AttestationDao
import com.neop2p.data.local.entity.PeerEntity
import com.neop2p.data.local.entity.TradeOfferEntity
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.local.entity.ConversationKeyEntity
import com.neop2p.data.local.entity.PendingMessageEntity
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.data.local.entity.DisputeEvidenceEntity
import com.neop2p.data.local.entity.ArbitratorDisputeEntity
import com.neop2p.data.local.entity.AttestationEntity

@Database(
    entities = [
        PeerEntity::class,
        TradeOfferEntity::class,
        ChatMessageEntity::class,
        ConversationKeyEntity::class,
        PendingMessageEntity::class,
        EscrowEntity::class,
        DisputeEvidenceEntity::class,
        ArbitratorDisputeEntity::class,
        AttestationEntity::class
    ],
    version = 22,
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
    abstract fun arbitratorDisputeDao(): ArbitratorDisputeDao
    abstract fun attestationDao(): AttestationDao

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

        /**
         * Add `matched_peer_id` to trade_offers (v12 → v13).
         *
         * Records which peer accepted/locked an offer (from the LXMF offer_status
         * status event) so the offer creator's chat routes to the acceptor
         * instead of to themselves.
         */
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trade_offers ADD COLUMN matched_peer_id TEXT")
            }
        }

        /**
         * Add signed peer attestations (v13 → v14).
         *
         * Stores local attestation attestation events received from the relay so the
         * profile screen can show them. Old rows are dropped on a later clear;
         * nothing here touches existing tables.
         */
        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS attestations (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "from_peer_id TEXT NOT NULL, " +
                        "target_peer_id TEXT NOT NULL, " +
                        "outcome TEXT NOT NULL, " +
                        "volume_sats INTEGER NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "signature_hex TEXT NOT NULL)"
                )
            }
        }

        /**
         * Add per-method payment details (bank number + holder name) to
         * trade_offers (v14 → v15).
         *
         * Exchanged via E2EE chat after a taker commits (never published to the
         * Nostr relay). Backfilled with "{}" for existing rows.
         */
        private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE trade_offers ADD COLUMN payment_details TEXT NOT NULL DEFAULT '{}'"
                )
            }
        }

        /**
         * Add buyer payment-window + confirmations columns to escrows (v15 → v16).
         *
         * `paid_at` records when the buyer marked the fiat payment as sent
         * (status PAID) so the payment window can be measured and the escrow
         * auto-disputes (never silently auto-refunds) if the seller stalls.
         * `required_confirmations` mirrors HodlHodl's configurable-confirmations
         * model; backfilled with 1 (the historical behavior).
         */
        private val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE escrows ADD COLUMN paid_at INTEGER")
                db.execSQL("ALTER TABLE escrows ADD COLUMN required_confirmations INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * User-selectable escrow funding script type (v16 → v17).
         *
         * `funding_script_type` records whether the 2-of-3 redeem script is
         * committed as P2SH ("LEGACY" → 2…/m… address) or P2WSH ("SEGWIT" →
         * bc1/tb1 address). Existing escrows default to LEGACY (historical
         * behavior — no address migration, funds stay where they are).
         */
        private val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE escrows ADD COLUMN funding_script_type TEXT NOT NULL DEFAULT 'LEGACY'"
                )
            }
        }

        /**
         * Add guided-flow receipt columns to escrows (v17 → v18).
         *
         * `receipt_sent_at` records when the buyer sent the payment receipt
         * (reference + optional screenshot); `receipt_reference` is the buyer's
         * unique payment reference code (the evidence anchor). Both are NULL for
         * pre-migration escrows — the guided receipt flow (Tasks 3+) fills them.
         */
        private val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE escrows ADD COLUMN receipt_sent_at INTEGER")
                db.execSQL("ALTER TABLE escrows ADD COLUMN receipt_reference TEXT")
                // W1: legacy rows can't crash on load — PAID was removed in v18;
                // remap it to CONFIRMING (the guided-flow successor state).
                db.execSQL("UPDATE escrows SET status = 'CONFIRMING' WHERE status = 'PAID'")
            }
        }

        /**
         * Two-party escrow columns (v18 → v19).
         *
         * `escrows.funding_vout` — on-chain output index of the funding tx that
         * pays the escrow address; recorded at funding verification so the
         * payout/refund spend the REAL deposit output (hardcoded vout 0 broke
         * funding txs with change outputs).
         * `escrows.buyer_btc_address` — the buyer's payout address collected at
         * accept time (U1); the payout sends tradeAmountSats here.
         * `trade_offers.btc_receive_address` — offer-level copy of the same
         * address (the seller's payout target when the creator is the buyer).
         */
        private val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE escrows ADD COLUMN funding_vout INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE escrows ADD COLUMN buyer_btc_address TEXT")
                db.execSQL("ALTER TABLE trade_offers ADD COLUMN btc_receive_address TEXT")
            }
        }

        /**
         * Refund destination for REFUND_TO_SELLER resolutions (v19 → v20).
         *
         * `escrows.refund_destination` — the seller's BTC address a refund
         * resolution must pay. Set by the arbitrator when publishing a
         * resolution (LXMF resolution message) so the party applying it refunds to the
         * SELLER, never to the resolver's own wallet (pre-v20 bug: the
         * refund tx was built to the LOCAL device's address, so an
         * arbitrator-applied refund paid the arbitrator).
         * `escrows.seller_refund_address` — the seller's own BTC address,
         * published by the seller's device via LXMF escrow_status so the buyer (and
         * via the dispute event, the arbitrator) can refund to the right
         * place without knowing the seller's key.
         */
        private val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE escrows ADD COLUMN refund_destination TEXT")
                db.execSQL("ALTER TABLE escrows ADD COLUMN seller_refund_address TEXT")
            }
        }

        // v20→v21: offer lifetime. NULL = legacy offer that never expires.
        private val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trade_offers ADD COLUMN expires_at INTEGER")
            }
        }

        private val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS arbitrator_disputes (" +
                        "escrow_id TEXT NOT NULL PRIMARY KEY, " +
                        "opened_by TEXT NOT NULL, " +
                        "reason TEXT NOT NULL, " +
                        "opened_at INTEGER NOT NULL, " +
                        "redeem_script_hex TEXT, " +
                        "psbt_hex TEXT, " +
                        "refund_tx_hex TEXT, " +
                        "deposit_sats INTEGER, " +
                        "funding_script_type TEXT, " +
                        "seller_refund_address TEXT, " +
                        "received_at INTEGER NOT NULL, " +
                        "resolved INTEGER NOT NULL DEFAULT 0)"
                )
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
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
                    // Downgrade safety (2026-09-02): a test build from a newer
                    // branch (e.g. app-flow-improvements' v23) left the on-device
                    // DB at a version above this build's. Room refuses to
                    // downgrade and the app crashed on every launch. The
                    // identity mnemonic + wallet keys live in SharedPreferences
                    // (KeyStore-encrypted), NOT in this DB — offers/escrows are
                    // transient market state — so a destructive downgrade is
                    // safe and keeps both phones' identities intact.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
                }
            }
        }
    }
}
