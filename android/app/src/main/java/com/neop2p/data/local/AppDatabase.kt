package com.neop2p.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.neop2p.data.local.dao.PeerDao
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.dao.PreKeyDao
import com.neop2p.data.local.dao.SessionDao
import com.neop2p.data.local.dao.SignedPreKeyDao
import com.neop2p.data.local.dao.IdentityKeyDao
import com.neop2p.data.local.dao.PendingMessageDao
import com.neop2p.data.local.entity.PeerEntity
import com.neop2p.data.local.entity.TradeOfferEntity
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.local.entity.DisputeEvidenceEntity
import com.neop2p.data.local.entity.PreKeyEntity
import com.neop2p.data.local.entity.SessionEntity
import com.neop2p.data.local.entity.SignedPreKeyEntity
import com.neop2p.data.local.entity.IdentityKeyEntity
import com.neop2p.data.local.entity.PendingMessageEntity

@Database(
    entities = [
        PeerEntity::class,
        TradeOfferEntity::class,
        EscrowEntity::class,
        ChatMessageEntity::class,
        DisputeEvidenceEntity::class,
        PreKeyEntity::class,
        SessionEntity::class,
        SignedPreKeyEntity::class,
        IdentityKeyEntity::class,
        PendingMessageEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun offerDao(): OfferDao
    abstract fun escrowDao(): EscrowDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun disputeEvidenceDao(): DisputeEvidenceDao
    abstract fun preKeyDao(): PreKeyDao
    abstract fun sessionDao(): SessionDao
    abstract fun signedPreKeyDao(): SignedPreKeyDao
    abstract fun identityKeyDao(): IdentityKeyDao
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: runBlocking(Dispatchers.IO) {
                    val passphrase = SqlCipherPassphraseManager.getPassphrase(context)
                    val factory = SupportFactory(passphrase)
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DB_NAME
                    )
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
                }
            }
        }
    }
}
