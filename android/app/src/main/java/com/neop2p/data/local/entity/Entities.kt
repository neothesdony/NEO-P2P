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
    val fee_percent: Double = 0.003,
    val fee_sats: Long = (crypto_amount_sats * fee_percent).toLong(),
    val fiat_methods: String = "[]",         // JSON array
    val status: String = "OPEN",
    val created_at: Long = System.currentTimeMillis(),
    val nostr_event_id: String? = null,
    val matched_peer_id: String? = null,
    // P2P payment details (bank number, holder name) keyed by fiat method id.
    // Serialized as JSON: {"bca":{"accountNumber":"...","accountHolder":"..."}}.
    // NEVER published to the Nostr relay — only exchanged via E2EE chat after
    // a taker commits (see P0-1).
    val payment_details: String = "{}"
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

// ─── On-chain 2-of-3 Multisig Escrow ───────────────────────────

@Entity(tableName = "escrows")
data class EscrowEntity(
    @PrimaryKey val escrow_id: String,
    val offer_id: String,
    val type: String = "ON_CHAIN",
    val funding_tx_id: String? = null,
    val payout_tx_id: String? = null,
    val funding_address: String? = null,
    val funding_address_path: String? = null,
    val funding_script_type: String = "LEGACY",
    val redeem_script_hex: String? = null,
    val psbt_unsigned: ByteArray? = null,
    val psbt_buyer_signed: ByteArray? = null,
    val deposit_amount_sats: Long,
    val trade_amount_sats: Long,
    val fee_amount_sats: Long,
    val network_fee_sats: Long = 0,
    val fee_address: String,
    val buyer_peer_id: String,
    val seller_peer_id: String,
    // P0-1: pubkeys authorized to sign for buyer / seller roles.
    val buyer_pubkey_hex: String? = null,
    val seller_pubkey_hex: String? = null,
    val status: String = "FUNDING",
    val buyer_signature: ByteArray? = null,
    val seller_signature: ByteArray? = null,
    val arbitrator_signature: ByteArray? = null,
    val arbitrator_decision: String? = null,
    val arbitrator_notes: String? = null,
    val channel_point: String? = null,
    val created_at: Long = System.currentTimeMillis(),
    val funded_at: Long? = null,
    val released_at: Long? = null,
    val paid_at: Long? = null,
    val receipt_sent_at: Long? = null,
    val receipt_reference: String? = null,
    val required_confirmations: Int = 1
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

// ─── Signed Peer Attestations (kind:33335) ────────────────────
// Received from the Nostr relay, signature-verified by ReputationSystem,
// and displayed on the profile screen. PK is (from, to, ts) so a peer's
// repeated re-announcements of the same attestation dedupe naturally.

@Entity(tableName = "attestations")
data class AttestationEntity(
    @PrimaryKey val id: String,          // "$fromPeer:$targetPeer:$timestamp"
    val from_peer_id: String,
    val target_peer_id: String,
    val outcome: String,                 // "POSITIVE" | "NEGATIVE"
    val volume_sats: Long,
    val timestamp: Long,
    val signature_hex: String
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

