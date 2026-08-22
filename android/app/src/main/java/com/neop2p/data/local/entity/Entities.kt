package com.neop2p.data.local.entity

import androidx.room.*
import com.neop2p.domain.model.*

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peer_id: String,
    val nickname: String,
    val nostr_pubkey: String,
    val ln_node_id: String = "",
    val created_at: Long = System.currentTimeMillis(),
    val reputation_score: Float = 0f,
    val total_trades: Int = 0,
    val last_seen: Long = System.currentTimeMillis(),
    val relay_hints: String = "[]",   // JSON array
    val multiaddrs: String = "[]"      // JSON array
)

@Entity(tableName = "trade_offers")
data class TradeOfferEntity(
    @PrimaryKey val offer_id: String,
    val creator_peer_id: String,
    val type: String,                        // "BUY" or "SELL"
    val asset: String = "BTC",
    val fiat_amount: Long,
    val crypto_amount_sats: Long,
    val price_per_unit: Double,
    val fee_percent: Double = 0.01,
    val fee_sats: Long = (crypto_amount_sats * fee_percent).toLong(),
    val fiat_methods: String = "[]",         // JSON array
    val status: String = "OPEN",
    val created_at: Long = System.currentTimeMillis(),
    val nostr_event_id: String? = null
)

@Entity(tableName = "escrows")
data class EscrowEntity(
    @PrimaryKey val escrow_id: String,
    val offer_id: String,
    val type: String = "ON_CHAIN",
    val funding_tx_id: String? = null,
    val payout_tx_id: String? = null,
    val funding_address: String? = null,
    val funding_address_path: String? = null,
    val redeem_script_hex: String? = null,
    val psbt_unsigned: ByteArray? = null,
    val psbt_buyer_signed: ByteArray? = null,
    val deposit_amount_sats: Long,
    val trade_amount_sats: Long,
    val fee_amount_sats: Long,
    val fee_address: String,
    val buyer_peer_id: String,
    val seller_peer_id: String,
    val status: String = "FUNDING",
    val buyer_signature: ByteArray? = null,
    val seller_signature: ByteArray? = null,
    val arbitrator_signature: ByteArray? = null,
    val arbitrator_decision: String? = null,
    val arbitrator_notes: String? = null,
    val channel_point: String? = null,
    val created_at: Long = System.currentTimeMillis(),
    val released_at: Long? = null
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val message_id: String,
    val offer_id: String,
    val sender_peer_id: String,
    val ciphertext: ByteArray,           // Encrypted by Signal Protocol
    val ratchet_key: ByteArray? = null,  // For decryption
    val is_read: Boolean = false,
    val sent_at: Long = System.currentTimeMillis(),
    val delivered_at: Long? = null,
    val file_attachment: ByteArray? = null  // Encrypted payment proof
)

@Entity(tableName = "dispute_evidence")
data class DisputeEvidenceEntity(
    @PrimaryKey val evidence_id: String,
    val escrow_id: String,
    val submitter_peer_id: String,
    val description: String,
    val mime_type: String = "image/jpeg",
    val image_data: ByteArray,
    val submitted_at: Long = System.currentTimeMillis()
)

// ─── E2EE Conversation Keys (NIP-44-style ECDH+XChaCha20) ─────

@Entity(tableName = "conversation_keys")
data class ConversationKeyEntity(
    @PrimaryKey val peerId: String,
    val theirPublicKey: ByteArray,
    val created_at: Long = System.currentTimeMillis()
)

// ─── Offline Message Queue ─────────────────────────────────────

@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    @PrimaryKey val message_id: String,
    val to_peer_id: String,
    val type: String,
    val payload: ByteArray,
    val created_at: Long = System.currentTimeMillis()
)

