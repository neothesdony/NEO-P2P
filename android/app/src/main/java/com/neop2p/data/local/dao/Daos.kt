package com.neop2p.data.local.dao

import androidx.room.*
import com.neop2p.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY last_seen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peer_id = :peerId")
    fun getPeer(peerId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE peer_id = :peerId")
    suspend fun getPeerSync(peerId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Delete
    suspend fun delete(peer: PeerEntity)
}

@Dao
interface OfferDao {
    @Query("SELECT * FROM trade_offers ORDER BY created_at DESC")
    fun getAllOffers(): Flow<List<TradeOfferEntity>>

    @Query("SELECT * FROM trade_offers WHERE offer_id = :offerId")
    fun getOffer(offerId: String): Flow<TradeOfferEntity?>

    @Query("SELECT * FROM trade_offers WHERE status = :status ORDER BY created_at DESC")
    fun getOffersByStatus(status: String): Flow<List<TradeOfferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(offer: TradeOfferEntity)

    @Query("UPDATE trade_offers SET status = :status WHERE offer_id = :offerId")
    suspend fun updateStatus(offerId: String, status: String)

    @Delete
    suspend fun delete(offer: TradeOfferEntity)
}

@Dao
interface EscrowDao {
    @Query("SELECT * FROM escrows ORDER BY created_at DESC")
    fun getAllEscrows(): Flow<List<EscrowEntity>>

    @Query("SELECT * FROM escrows ORDER BY created_at DESC")
    suspend fun getAllEscrowsSync(): List<EscrowEntity>

    @Query("SELECT * FROM escrows WHERE escrow_id = :escrowId")
    fun getEscrow(escrowId: String): Flow<EscrowEntity?>

    @Query("SELECT * FROM escrows WHERE escrow_id = :escrowId")
    suspend fun getEscrowSync(escrowId: String): EscrowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(escrow: EscrowEntity)

    @Query("UPDATE escrows SET status = :status WHERE escrow_id = :escrowId")
    suspend fun updateStatus(escrowId: String, status: String)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE offer_id = :offerId ORDER BY sent_at ASC")
    fun getMessages(offerId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE offer_id = :offerId AND is_read = 0")
    fun getUnreadMessages(offerId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Query("UPDATE chat_messages SET is_read = 1 WHERE offer_id = :offerId")
    suspend fun markAsRead(offerId: String)
}

// ─── Signal Protocol DAOs ───────────────────────────────────────

@Dao
interface SignalPreKeyDao {
    @Query("SELECT * FROM signal_pre_keys WHERE pre_key_id = :id")
    suspend fun load(id: Int): SignalPreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SignalPreKeyEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM signal_pre_keys WHERE pre_key_id = :id)")
    suspend fun contains(id: Int): Boolean

    @Query("DELETE FROM signal_pre_keys WHERE pre_key_id = :id")
    suspend fun remove(id: Int)
}

@Dao
interface SignalSignedPreKeyDao {
    @Query("SELECT * FROM signal_signed_pre_keys WHERE signed_pre_key_id = :id")
    suspend fun load(id: Int): SignalSignedPreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SignalSignedPreKeyEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM signal_signed_pre_keys WHERE signed_pre_key_id = :id)")
    suspend fun contains(id: Int): Boolean

    @Query("DELETE FROM signal_signed_pre_keys WHERE signed_pre_key_id = :id")
    suspend fun remove(id: Int)
}

@Dao
interface SignalIdentityDao {
    @Query("SELECT * FROM signal_identity WHERE id = 1")
    suspend fun load(): SignalIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SignalIdentityEntity)
}

@Dao
interface SignalSessionDao {
    @Query("SELECT * FROM signal_sessions WHERE peer_id = :peerId AND device_id = :deviceId")
    suspend fun load(peerId: String, deviceId: Int): SignalSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SignalSessionEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM signal_sessions WHERE peer_id = :peerId AND device_id = :deviceId)")
    suspend fun contains(peerId: String, deviceId: Int): Boolean

    @Query("DELETE FROM signal_sessions WHERE peer_id = :peerId AND device_id = :deviceId")
    suspend fun remove(peerId: String, deviceId: Int)

    @Query("DELETE FROM signal_sessions WHERE peer_id = :peerId")
    suspend fun removeAll(peerId: String)
}

@Dao
interface SignalTrustedIdentityDao {
    @Query("SELECT * FROM signal_trusted_identities WHERE peer_id = :peerId")
    suspend fun load(peerId: String): SignalTrustedIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SignalTrustedIdentityEntity)
}
