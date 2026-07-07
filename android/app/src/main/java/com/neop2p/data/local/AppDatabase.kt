package com.neop2p.data.local

import android.content.Context
import androidx.room.*
import com.neop2p.data.local.dao.*
import com.neop2p.data.local.entity.*
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        PeerEntity::class,
        TradeOfferEntity::class,
        EscrowEntity::class,
        ChatMessageEntity::class,
        PaymentProofEntity::class,
        SignalPreKeyEntity::class,
        SignalSignedPreKeyEntity::class,
        SignalIdentityEntity::class,
        SignalSessionEntity::class,
        SignalTrustedIdentityEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun offerDao(): OfferDao
    abstract fun escrowDao(): EscrowDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun signalPreKeyDao(): SignalPreKeyDao
    abstract fun signalSignedPreKeyDao(): SignalSignedPreKeyDao
    abstract fun signalIdentityDao(): SignalIdentityDao
    abstract fun signalSessionDao(): SignalSessionDao
    abstract fun signalTrustedIdentityDao(): SignalTrustedIdentityDao

    companion object {
        private const val DB_NAME = "neop2p.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            // Derive passphrase from KeyStore identity key. Never hardcoded.
            // Same identity always produces same passphrase.
            val passphrase = runBlocking { SqlCipherPassphraseManager.getPassphrase(context) }
            val factory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
