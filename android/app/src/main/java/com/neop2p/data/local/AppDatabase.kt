package com.neop2p.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.neop2p.data.local.dao.PeerDao
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.entity.PeerEntity
import com.neop2p.data.local.entity.TradeOfferEntity
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.data.local.entity.ChatMessageEntity
import com.neop2p.data.local.entity.DisputeEvidenceEntity

@Database(
    entities = [
        PeerEntity::class,
        TradeOfferEntity::class,
        EscrowEntity::class,
        ChatMessageEntity::class,
        DisputeEvidenceEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun offerDao(): OfferDao
    abstract fun escrowDao(): EscrowDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun disputeEvidenceDao(): DisputeEvidenceDao

    companion object {
        private const val DB_NAME = "neop2p.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
