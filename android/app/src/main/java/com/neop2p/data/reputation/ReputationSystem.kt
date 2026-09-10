package com.neop2p.data.reputation

import android.util.Log
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.entity.PeerEntity
import com.neop2p.data.p2p.IdentityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-first reputation with signed attestations.
 *
 * After each trade, both peers sign an attestation with their BIP-340
 * Schnorr key (derived from the BIP-39 identity):
 *   { fromPeer, targetPeer, outcome, volumeSats, timestamp, pubkey, signature }
 *
 * Attestations are persisted locally (Room/SQLCipher `attestations` table)
 * and exchanged with the counterparty over LXMF DIRECT signaling
 * (title = "attestation"). Signatures are verified against the pubkey
 * carried in the payload, bound to the sender's peerId by the
 * authenticated LXMF sender identity (RNS-era peers store no pubkey).
 */
@Singleton
class ReputationSystem @Inject constructor(
    private val identityManager: IdentityManager,
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "ReputationSystem"
    }

    data class PeerReputation(
        val peerId: String,
        val displayName: String = "",
        val score: Float = 0f,
        val totalTrades: Int = 0,
        val positiveTrades: Int = 0,
        val negativeTrades: Int = 0,
        val totalVolumeSats: Long = 0L,
        val isNew: Boolean = true
    )

    data class Attestation(
        val fromPeer: String,
        val targetPeer: String,
        val outcome: AttestationOutcome,
        val volumeSats: Long,
        val timestamp: Long,
        val signature: ByteArray
    )

    enum class AttestationOutcome { POSITIVE, NEGATIVE }

    private val _reputations = MutableStateFlow<Map<String, PeerReputation>>(emptyMap())
    val reputations: StateFlow<Map<String, PeerReputation>> = _reputations.asStateFlow()

    private val _incomingAttestations = MutableSharedFlow<Attestation>(replay = 100)
    val incomingAttestations: SharedFlow<Attestation> = _incomingAttestations.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize by loading reputation data from DB.
     */
    suspend fun initialize() {
        try {
            val peers = db.peerDao().getAllPeers().first()
            // Heal: recompute trade counts from the attestations table (ground
            // truth). Pre-dedup builds re-processed relay-replayed attestations
            // on every refresh, inflating total_trades (1 trade → 88 → 108).
            // The attestations table is IGNORE-deduped by (from,target,ts), so
            // the row count per target IS the true trade count.
            val attestations = db.attestationDao().getAllAttestations().first()
            val positiveCounts = attestations
                .filter { it.outcome == "POSITIVE" }
                .groupingBy { it.target_peer_id }
                .eachCount()
            val negativeCounts = attestations
                .filter { it.outcome == "NEGATIVE" }
                .groupingBy { it.target_peer_id }
                .eachCount()
            val initialReputations = peers.associate { peer ->
                val positive = positiveCounts[peer.peer_id] ?: 0
                val negative = negativeCounts[peer.peer_id] ?: 0
                val total = positive + negative
                peer.peer_id to PeerReputation(
                    peerId = peer.peer_id,
                    displayName = peer.nickname,
                    score = calculateScore(positive, negative),
                    totalTrades = total,
                    totalVolumeSats = 0L,
                    isNew = total == 0
                )
            }
            _reputations.value = initialReputations
            // Persist the healed counts so the DB row matches the UI.
            initialReputations.forEach { (peerId, rep) ->
                val existing = db.peerDao().getPeerSync(peerId) ?: return@forEach
                if (existing.total_trades != rep.totalTrades ||
                    existing.reputation_score != rep.score
                ) {
                    db.peerDao().upsert(existing.copy(
                        total_trades = rep.totalTrades,
                        reputation_score = rep.score
                    ))
                }
            }
            Log.d(TAG, "Loaded ${initialReputations.size} peer reputations from DB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load reputations from DB", e)
        }
    }

    /**
     * Create a signed attestation after a trade completes.
     * Signs the attestation data with the peer's Ed25519 key
     * (derived from BIP-32 path m/44'/888'/0'/0/0).
     */
    suspend fun createAttestation(
        myPeerId: String,
        targetPeerId: String,
        wasPositive: Boolean,
        volumeSats: Long
    ): Attestation {
        val timestamp = System.currentTimeMillis()
        val outcome = if (wasPositive) AttestationOutcome.POSITIVE else AttestationOutcome.NEGATIVE
        val attestationData = buildAttestationData(
            fromPeer = myPeerId,
            targetPeer = targetPeerId,
            outcome = outcome,
            volumeSats = volumeSats,
            timestamp = timestamp
        )
        val signature = signAttestation(attestationData)
        val attestation = Attestation(
            fromPeer = myPeerId,
            targetPeer = targetPeerId,
            outcome = outcome,
            volumeSats = volumeSats,
            timestamp = timestamp,
            signature = signature
        )
        // Persist the signed proof — the profile attestation viewer and the
        // initialize() heal read this table. IGNORE-deduped by PK
        // (from,target,ts), so re-creating the same attestation is a no-op.
        db.attestationDao().insert(
            com.neop2p.data.local.entity.AttestationEntity(
                id = "$myPeerId:$targetPeerId:$timestamp",
                from_peer_id = myPeerId,
                target_peer_id = targetPeerId,
                outcome = outcome.name,
                volume_sats = volumeSats,
                timestamp = timestamp,
                signature_hex = AttestationCodec.signatureHex(signature)
            )
        )
        // Update local reputation and persist
        updateLocalReputation(targetPeerId, wasPositive, volumeSats)
        Log.d(TAG, "Attestation created + persisted: $myPeerId → $targetPeerId (${attestation.outcome})")
        return attestation
    }

    /** Serialize a created attestation for the LXMF wire (Task 4 sends it). */
    fun toWireJson(attestation: Attestation): String = AttestationCodec.buildPayload(
        fromPeer = attestation.fromPeer,
        targetPeer = attestation.targetPeer,
        outcome = attestation.outcome.name,
        volumeSats = attestation.volumeSats,
        timestamp = attestation.timestamp,
        pubkeyHex = identityManager.getNostrKeyPair().publicKeyHex,
        signatureHex = AttestationCodec.signatureHex(attestation.signature)
    )

    /**
     * Process an inbound attestation (LXMF "attestation" signaling).
     * Verifies the BIP-340 signature against the payload pubkey, enforces
     * sender-authentication (the LXMF sender must BE the signer), rejects
     * self-ratings and pubkey rotation against a pinned stored key, and
     * dedupes via the IGNORE-deduped table PK (from,target,ts) so LXMF
     * re-deliveries never double-count.
     */
    suspend fun processAttestation(json: String, senderPeerId: String) {
        try {
            val payload = AttestationCodec.parsePayload(json) ?: run {
                Log.w(TAG, "Attestation from $senderPeerId: unparseable payload — rejecting")
                return
            }
            val storedPubkey = runCatching {
                db.peerDao().getPeerSync(payload.fromPeer)?.nostr_pubkey
            }.getOrNull()
            when (AttestationCodec.validate(payload, senderPeerId, storedPubkey)) {
                AttestationCodec.AttestationValidation.WRONG_SENDER -> {
                    Log.w(TAG, "Attestation sender mismatch: LXMF sender $senderPeerId != from_peer ${payload.fromPeer} — rejecting")
                    return
                }
                AttestationCodec.AttestationValidation.SELF_RATING -> {
                    Log.w(TAG, "Attestation self-rating from ${payload.fromPeer} — rejecting")
                    return
                }
                AttestationCodec.AttestationValidation.KEY_MISMATCH -> {
                    Log.w(TAG, "Attestation pubkey ${payload.pubkeyHex.take(12)}… contradicts stored key for ${payload.fromPeer} — rejecting")
                    return
                }
                AttestationCodec.AttestationValidation.OK -> Unit
            }
            val data = AttestationCodec.canonicalData(
                payload.fromPeer, payload.targetPeer, payload.outcome,
                payload.volumeSats, payload.timestamp
            )
            if (!AttestationCodec.verify(payload.pubkeyHex, data, payload.signatureHex)) {
                Log.w(TAG, "Attestation from ${payload.fromPeer} has invalid signature — rejecting")
                return
            }
            // Adopt the pubkey when the peer row has none (TOFU) — the
            // next attestation from this peer is then pinned to it.
            if (storedPubkey.isNullOrBlank()) {
                runCatching {
                    val existing = db.peerDao().getPeerSync(payload.fromPeer)
                    if (existing != null) {
                        db.peerDao().upsert(existing.copy(nostr_pubkey = payload.pubkeyHex))
                    }
                }
            }
            // IGNORE-deduped insert: -1L means the row already exists
            // (LXMF re-delivery) — skip the recount.
            val inserted = db.attestationDao().insert(
                com.neop2p.data.local.entity.AttestationEntity(
                    id = "${payload.fromPeer}:${payload.targetPeer}:${payload.timestamp}",
                    from_peer_id = payload.fromPeer,
                    target_peer_id = payload.targetPeer,
                    outcome = payload.outcome,
                    volume_sats = payload.volumeSats,
                    timestamp = payload.timestamp,
                    signature_hex = payload.signatureHex
                )
            )
            if (inserted == -1L) {
                Log.d(TAG, "Attestation ${payload.fromPeer}→${payload.targetPeer} already processed — skipping")
                return
            }
            updateLocalReputation(
                payload.targetPeer,
                payload.outcome == AttestationOutcome.POSITIVE.name,
                payload.volumeSats
            )
            _incomingAttestations.emit(
                Attestation(
                    fromPeer = payload.fromPeer,
                    targetPeer = payload.targetPeer,
                    outcome = if (payload.outcome == AttestationOutcome.POSITIVE.name)
                        AttestationOutcome.POSITIVE else AttestationOutcome.NEGATIVE,
                    volumeSats = payload.volumeSats,
                    timestamp = payload.timestamp,
                    signature = AttestationCodec.hexToBytes(payload.signatureHex)
                )
            )
            Log.d(TAG, "Processed attestation for ${payload.targetPeer}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process attestation", e)
        }
    }

    /** Canonical signed bytes — delegated to the pure codec. */
    private fun buildAttestationData(
        fromPeer: String,
        targetPeer: String,
        outcome: AttestationOutcome,
        volumeSats: Long,
        timestamp: Long
    ): ByteArray = AttestationCodec.canonicalData(
        fromPeer, targetPeer, outcome.name, volumeSats, timestamp
    )

    /**
     * Sign attestation data with the peer's Nostr (BIP-340 Schnorr / secp256k1)
     * key derived from the BIP-32 identity. Verification uses the peer's
     * x-only secp256k1 public key, which is exactly what is persisted in
     * `PeerEntity.nostr_pubkey` (64 hex chars = 32 bytes).
     *
     * NOTE: this was previously signed with the libp2p Ed25519 key but verified
     * against the Nostr secp256k1 pubkey bytes — a type mismatch that made every
     * remote attestation fail silently. Now sign+verify use the same key type.
     */
    private fun signAttestation(data: ByteArray): ByteArray {
        return try {
            val keyPair = identityManager.getNostrKeyPair()
            val privKey = hexToBytes(keyPair.privateKeyHex)
            val auxRand = java.security.SecureRandom().generateSeed(32)
            val signature = com.neop2p.data.p2p.Schnorr.sign(privKey, data, auxRand)
            Log.d(TAG, "Attestation signed via BIP-340 Schnorr (${signature.size}-byte signature)")
            signature
        } catch (e: Exception) {
            Log.e(TAG, "Attestation signing failed", e)
            ByteArray(0)
        }
    }

    /** Verify a BIP-340 Schnorr signature against the pubkey carried in the payload. */
    private fun verifyAttestation(
        data: ByteArray,
        signature: ByteArray,
        pubkeyHex: String
    ): Boolean = AttestationCodec.verify(pubkeyHex, data, AttestationCodec.signatureHex(signature))

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    /**
     * Update the local reputation database for a peer and persist to Room.
     */
    private fun updateLocalReputation(peerId: String, wasPositive: Boolean, volumeSats: Long) {
        _reputations.update { map ->
            val current = map[peerId] ?: PeerReputation(peerId = peerId)
            val updated = current.copy(
                totalTrades = current.totalTrades + 1,
                positiveTrades = current.positiveTrades + if (wasPositive) 1 else 0,
                negativeTrades = current.negativeTrades + if (wasPositive) 0 else 1,
                totalVolumeSats = current.totalVolumeSats + volumeSats,
                isNew = false,
                score = calculateScore(
                    current.positiveTrades + (if (wasPositive) 1 else 0),
                    current.negativeTrades + (if (wasPositive) 0 else 1)
                )
            )
            map + (peerId to updated)
        }

        // Persist to DB
        scope.launch {
            try {
                val rep = _reputations.value[peerId] ?: return@launch
                val existing = db.peerDao().getPeerSync(peerId)
                val entity = PeerEntity(
                    peer_id = peerId,
                    nickname = rep.displayName.ifEmpty { existing?.nickname ?: "" },
                    nostr_pubkey = existing?.nostr_pubkey ?: "",
                    ln_node_id = existing?.ln_node_id ?: "",
                    reputation_score = rep.score,
                    total_trades = rep.totalTrades,
                    last_seen = System.currentTimeMillis(),
                    relay_hints = existing?.relay_hints ?: "[]",
                    multiaddrs = existing?.multiaddrs ?: "[]"
                )
                db.peerDao().upsert(entity)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist reputation update", e)
            }
        }
    }

    /**
     * Get this peer's own reputation.
     */
    fun getMyReputation(myPeerId: String): ReputationProfile =
        reputationProfileFor(myPeerId, _reputations.value)

    /**
     * Calculate reputation score from trade history using Wilson score interval.
     */
    private fun calculateScore(positive: Int, negative: Int): Float {
        val total = positive.toLong() + negative.toLong()
        if (total == 0L) return 0f

        // Wilson score interval (lower bound) for 95% confidence
        val z = 1.96
        val p = positive.toDouble() / total
        val left = p + (z * z) / (2 * total)
        val right = z * Math.sqrt((p * (1 - p) + (z * z) / (4 * total)) / total)
        val under = 1 + (z * z) / total

        return ((left - right) / under).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Get reputation for a specific peer.
     */
    fun getReputation(peerId: String): PeerReputation =
        _reputations.value[peerId] ?: PeerReputation(peerId = peerId)

    /**
     * Clear local reputation data (privacy option).
     */
    fun resetLocalReputations() {
        _reputations.update { emptyMap() }
        Log.d(TAG, "Local reputation data cleared")
    }
}

data class ReputationProfile(
    val peerId: String,
    val score: Float,
    val totalTrades: Int,
    val completedTrades: Int,
    val disputedTrades: Int
)

/**
 * Pure mapping from the in-memory reputation map to a [ReputationProfile]
 * for one peer. A peer with no reputation yet defaults to a 1.0 score with
 * zero trades — the profile UI renders "—" for zero trades, so the 1.0
 * default is masked (same semantics as the old getMyReputation).
 */
fun reputationProfileFor(
    myPeerId: String,
    reputations: Map<String, ReputationSystem.PeerReputation>
): ReputationProfile {
    val rep = reputations[myPeerId]
    return ReputationProfile(
        peerId = myPeerId,
        score = rep?.score ?: 1.0f,
        totalTrades = rep?.totalTrades ?: 0,
        completedTrades = rep?.positiveTrades ?: 0,
        disputedTrades = rep?.negativeTrades ?: 0
    )
}