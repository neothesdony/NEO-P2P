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

@Dao
interface DisputeEvidenceDao {
    @Query("SELECT * FROM dispute_evidence WHERE escrow_id = :escrowId ORDER BY submitted_at ASC")
    suspend fun getEvidenceForEscrow(escrowId: String): List<DisputeEvidenceEntity>

    @Query("SELECT * FROM dispute_evidence WHERE escrow_id = :escrowId ORDER BY submitted_at ASC")
    fun observeEvidenceForEscrow(escrowId: String): Flow<List<DisputeEvidenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DisputeEvidenceEntity)

    @Delete
    suspend fun delete(entity: DisputeEvidenceEntity)
}

// ─── Signal Protocol Store DAOs ──────────────────────────────

@Dao
interface PreKeyDao {
    @Query("SELECT * FROM signal_pre_keys WHERE preKeyId = :preKeyId")
    suspend fun load(preKeyId: Int): PreKeyEntity?

    @Query("SELECT * FROM signal_pre_keys")
    suspend fun loadAll(): List<PreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: PreKeyEntity)

    @Query("DELETE FROM signal_pre_keys WHERE preKeyId = :preKeyId")
    suspend fun remove(preKeyId: Int)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM signal_sessions WHERE peerId = :peerId AND deviceId = :deviceId")
    suspend fun load(peerId: String, deviceId: Int = 1): SessionEntity?

    @Query("SELECT * FROM signal_sessions")
    suspend fun loadAll(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SessionEntity)

    @Query("DELETE FROM signal_sessions WHERE peerId = :peerId AND deviceId = :deviceId")
    suspend fun delete(peerId: String, deviceId: Int = 1)
}

@Dao
interface SignedPreKeyDao {
    @Query("SELECT * FROM signal_signed_pre_keys WHERE signedPreKeyId = :signedPreKeyId")
    suspend fun load(signedPreKeyId: Int): SignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_pre_keys")
    suspend fun loadAll(): List<SignedPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SignedPreKeyEntity)

    @Query("DELETE FROM signal_signed_pre_keys WHERE signedPreKeyId = :signedPreKeyId")
    suspend fun remove(signedPreKeyId: Int)
}

@Dao
interface IdentityKeyDao {
    @Query("SELECT * FROM signal_identity_keys WHERE id = :id")
    suspend fun load(id: Int = 1): IdentityKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: IdentityKeyEntity)

    @Query("DELETE FROM signal_identity_keys")
    suspend fun clear()
}

