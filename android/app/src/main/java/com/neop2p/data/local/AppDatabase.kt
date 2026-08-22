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
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.dao.ConversationKeyDao
import com.neop2p.data.local.dao.PendingMessageDao
import com.neop2p.data.local.entity.PeerEntity
import com.neop2p.data.local.entity.TradeOfferEntity
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.local.entity.DisputeEvidenceEntity
import com.neop2p.data.local.entity.ConversationKeyEntity
import com.neop2p.data.local.entity.PendingMessageEntity

@Database(
    entities = [
        PeerEntity::class,
        TradeOfferEntity::class,
        EscrowEntity::class,
        ChatMessageEntity::class,
        DisputeEvidenceEntity::class,
        ConversationKeyEntity::class,
        PendingMessageEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun offerDao(): OfferDao
    abstract fun escrowDao(): EscrowDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun disputeEvidenceDao(): DisputeEvidenceDao
    abstract fun conversationKeyDao(): ConversationKeyDao
    abstract fun pendingMessageDao(): PendingMessageDao

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
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                    .also { INSTANCE = it }
                }
            }
        }
    }
}
