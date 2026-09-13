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
    val fee_percent: Double = 0.005,
    val fee_sats: Long = (crypto_amount_sats * 5) / 1000,
    val fiat_methods: String = "[]",         // JSON array
    val status: String = "OPEN",
    val created_at: Long = System.currentTimeMillis(),
    val nostr_event_id: String? = null,
    val matched_peer_id: String? = null,
    // BTC receive address for the trade — used as the buyer's payout
    // destination on SELL offers. Populated at accept time by the buyer
    // (U1); never published to the Nostr relay (transported via LXMF escrow_status
    // escrow status events, E2EE chat, or local persistence).
    val btc_receive_address: String? = null,
    // P2P payment details (bank number, holder name) keyed by fiat method id.
    // Serialized as JSON: {"bca":{"accountNumber":"...","accountHolder":"..."}}.
    // NEVER published to the Nostr relay — only exchanged via E2EE chat after
    // a taker commits (see P0-1).
    val payment_details: String = "{}",
    // Offer lifetime (epoch millis). NULL = never expires (legacy offers).
    // The creator picks a TTL at create time; the relay carries it so both
    // sides converge on the same deadline. Stale offers stay visible but
    // cannot be claimed past this time.
    val expires_at: Long? = null,
    // Epoch millis when the offer became MATCHED. NULL = not locked (or an
    // unlocked/legacy row). Drives the locked-offer auto-expiry: a MATCHED
    // offer whose escrow is never created within MATCHED_ESCROW_TIMEOUT_MS
    // is auto-CANCELLED by the orchestrator sweep. Local-only lifecycle
    // metadata (like matched_peer_id) — never published to the feed.
    val locked_at: Long? = null,
    // C1 (2026-09-11): creator's secp256k1 pubkey (from the offer JSON) and
    // the matched buyer's pubkey (from the MATCHED offer_status event). The
    // escrow's 2-of-3 must use the REAL buyer key — never the seller's own.
    val creator_pubkey_hex: String? = null,
    val buyer_pubkey_hex: String? = null,
    // F2 (2026-09-12): the buyer's role-signed attestation of its payout
    // address (scope = offerId), delivered via escrow_status / offer payloads.
    // NULL for legacy rows / older counterparties.
    val buyer_address_attestation: String? = null
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
    val required_confirmations: Int = 1,
    // On-chain output index of the funding tx that pays this escrow's
    // funding_address. Recorded at funding verification (Task 3) so the
    // payout and refund spend the REAL deposit output, not hardcoded vout 0.
    val funding_vout: Long = 0L,
    // The buyer's BTC payout address (collected at accept time, U1). The
    // payout sends tradeAmountSats here; never the escrow's own P2SH address.
    val buyer_btc_address: String? = null,
    // Refund destination for REFUND_TO_SELLER resolutions. Set by the
    // arbitrator when publishing a resolution (LXMF resolution message) so the party
    // applying it refunds to the SELLER's address — never the resolver's
    // own wallet (the pre-v20 bug refunded to whoever applied the decision).
    val refund_destination: String? = null,
    // The seller's own BTC refund address, published by the seller's device
    // via LXMF escrow_status so the buyer (and via the dispute event, the arbitrator)
    // can refund to the right place without knowing the seller's key.
    val seller_refund_address: String? = null,
    // The ACTUAL on-chain value of the funding output (2026-09-04). Equals
    // deposit_amount_sats for exact deposits; HIGHER when the seller overpaid.
    // The payout/refund spend this value and return the excess to the seller.
    val funded_amount_sats: Long? = null,
    // F2 (2026-09-12): role-signed destination attestations. The seller signs
    // its refund address (scope = escrowId); the buyer signs its payout address
    // (scope = offerId). Both travel in escrow_status so the arbitrator can be
    // certain where a refund/payout MUST go — never to an attacker-supplied
    // destination. NULL for legacy rows / older counterparties.
    val seller_refund_attestation: String? = null,
    val buyer_address_attestation: String? = null,
    // When the escrow was moved to DISPUTED (F-1/D1, 2026-09-13). Disputes have
    // no deadline, so the UI renders how long one has been waiting. NULL for
    // non-disputed / legacy rows.
    val disputed_at: Long? = null
)

@Entity(tableName = "arbitrator_disputes")
data class ArbitratorDisputeEntity(
    @PrimaryKey val escrow_id: String,
    val opened_by: String,
    val reason: String,
    val opened_at: Long,
    val redeem_script_hex: String? = null,
    val psbt_hex: String? = null,
    val refund_tx_hex: String? = null,
    val deposit_sats: Long? = null,
    val funding_script_type: String? = null,
    val seller_refund_address: String? = null,
    // The escrow's parties (v23, 2026-09-02). Carried by the dispute event so
    // the arbitrator — who has NO local escrow row — can deliver the
    // resolution to the buyer AND seller (pre-v23 the resolution was sent to
    // nobody and funds stayed locked in the multisig forever).
    val buyer_peer_id: String? = null,
    val seller_peer_id: String? = null,
    // F2 (2026-09-12): the parties' escrow keys + destinations so the
    // arbitrator (who has no local escrow row) can verify role attestations
    // and build the correct payout/refund. Populated from the dispute event.
    val buyer_btc_address: String? = null,
    val buyer_pubkey_hex: String? = null,
    val seller_pubkey_hex: String? = null,
    val seller_refund_attestation: String? = null,
    val buyer_address_attestation: String? = null,
    val offer_id: String? = null,
    val trade_sats: Long? = null,
    val received_at: Long = System.currentTimeMillis(),
    val resolved: Boolean = false
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

// ─── Signed Peer Attestations (local attestation) ────────────────────
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

