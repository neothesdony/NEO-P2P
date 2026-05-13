package com.neop2p.data.local

import android.content.Context
import androidx.room.*
import com.neop2p.data.local.dao.*
import com.neop2p.data.local.entity.*
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        PeerEntity::class,
        TradeOfferEntity::class,
        EscrowEntity::class,
        ChatMessageEntity::class,
        PaymentProofEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun offerDao(): OfferDao
    abstract fun escrowDao(): EscrowDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        private const val DB_NAME = "neop2p.db"
        private const val PASSPHRASE = "neop2p_local_encryption_key_v1"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val passphrase = PASSPHRASE.encodeToByteArray()
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
