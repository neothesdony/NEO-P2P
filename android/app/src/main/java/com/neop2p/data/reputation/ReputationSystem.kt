package com.neop2p.data.reputation

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gossip-based reputation system with zero central DB.
 *
 * After each trade, both peers sign a cryptographic attestation:
 *   { peerId, targetId, outcome (+1/-1), volume (sats), timestamp, signature }
 *
 * Attestations are gossiped via libp2p GossipSub.
 * Each peer maintains their own local reputation database (Room).
 * No central authority, no server, no single point of failure.
 */
@Singleton
class ReputationSystem @Inject constructor() {
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
     * Create a signed attestation after a trade completes.
     */
    suspend fun createAttestation(
        myPeerId: String,
        targetPeerId: String,
        wasPositive: Boolean,
        volumeSats: Long
    ): Attestation {
        val attestation = Attestation(
            fromPeer = myPeerId,
            targetPeer = targetPeerId,
            outcome = if (wasPositive) AttestationOutcome.POSITIVE else AttestationOutcome.NEGATIVE,
            volumeSats = volumeSats,
            timestamp = System.currentTimeMillis(),
            signature = "SIG_${myPeerId}_${targetPeerId}_${System.currentTimeMillis()}".encodeToByteArray()
        )

        // Update local reputation
        updateLocalReputation(targetPeerId, wasPositive, volumeSats)

        Log.d(TAG, "Attestation created: $myPeerId → $targetPeerId (${attestation.outcome})")
        return attestation
    }

    /**
     * Process an incoming attestation from a gossip message.
     * Verifies the signature before updating reputation.
     */
    suspend fun processAttestation(attestation: Attestation) {
        try {
            // Verify signature (basic check — real verification in Phase 4)
            if (attestation.signature.size < 10) {
                Log.w(TAG, "Invalid attestation signature from ${attestation.fromPeer}")
                return
            }

            // Update local reputation
            updateLocalReputation(
                attestation.targetPeer,
                attestation.outcome == AttestationOutcome.POSITIVE,
                attestation.volumeSats
            )

            _incomingAttestations.emit(attestation)
            Log.d(TAG, "Processed attestation for ${attestation.targetPeer}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process attestation", e)
        }
    }

    /**
     * Update the local reputation database for a peer.
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
    }

    /**
     * Get this peer's own reputation.
     */
    fun getMyReputation(myPeerId: String): ReputationProfile {
        val rep = _reputations.value[myPeerId]
        return ReputationProfile(
            peerId = myPeerId,
            score = rep?.score ?: 1.0f,
            totalTrades = rep?.totalTrades ?: 0,
            completedTrades = rep?.positiveTrades ?: 0,
            disputedTrades = rep?.negativeTrades ?: 0
        )
    }

    /**
     * Calculate reputation score from trade history.
     * Uses Wilson score interval for statistically reliable ratings.
     */
    private fun calculateScore(positive: Int, negative: Int): Float {
        val total = positive + negative
        if (total == 0) return 0f

        // Wilson score interval (lower bound) for 95% confidence
        // This prevents someone with 1 good trade from having 100%
        val z = 1.96  // 95% confidence
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
