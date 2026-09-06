package com.neop2p.data.p2p.routing

import com.neop2p.data.p2p.RnsOfferDigest
import com.neop2p.domain.model.OfferStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure decision logic for the offer-feed digest consumer (Phase 3 feed, fixed
 * 2026-09-02 for 3rd-device convergence).
 *
 * The feed announces a digest per offer on a paced loop. When an offer is
 * locked (MATCHED/ESCROWED) or terminal (COMPLETED/CANCELLED), the announcing
 * peer used to stop re-announcing it entirely — so non-participant peers
 * never learned the status change and kept the stale OPEN row (Accept button
 * live on a dead trade). Two mechanisms fix that:
 *
 *  1. HASH-CHANGED REFETCH — the digest commitment embeds the offer status
 *     (`RnsOfferDigest.canonicalJson` carries `status`), so a status change
 *     changes the hash. A peer that already holds the offer and sees a
 *     digest with a DIFFERENT hash re-fetches the full JSON over LXMF and
 *     re-ingests it (the ingest pipeline applies the announced status to an
 *     OPEN row, never downgrades a locked status).
 *
 *  2. TERMINAL TOMBSTONE — the creator re-announces the offer once per
 *     sweep tick (~60s) as a tombstone digest (`{v,id,t:true}`) while it
 *     holds a COMPLETED/CANCELLED offer row. Receivers that hold the row
 *     transition it to the terminal status WITHOUT fetching (no payload),
 *     which heals rows that missed the earlier hash-changed digest. A
 *     tombstone never creates or resurrects a row.
 *
 *  3. OBSERVER DELETION — a non-party receiver (neither creator nor
 *     matched peer) has no business keeping a finished trade's offer row:
 *     the tombstone DELETES it so the offer disappears from their feed
 *     entirely instead of lingering as a locked/terminal row. Party rows
 *     are kept (marked COMPLETED) — the buyer's escrow detail reads fiat
 *     and bank details from the offer row, and the creator's own row is
 *     their history.
 *
 * Pure and JVM-testable (mirrors the OfferClaimGate style).
 */
object OfferFeedGate {

    /**
     * Whether a digest for an offer we already hold must be re-fetched.
     * True when the digest is a live commitment whose hash differs from the
     * stored row's canonical hash (the offer changed: status or fields).
     * Tombstones never trigger a fetch (terminal status applies locally).
     *
     * @param localStatus    current stored status (null when no row exists —
     *                       the caller handles fresh inserts separately)
     * @param storedHash     the digest commitment hash the stored row would
     *                       announce (computed by the caller from the row +
     *                       creator nickname, so the comparison uses the same
     *                       canonical serializer the creator used)
     * @param incomingDigest the digest just received from the feed
     */
    fun needsReFetch(
        localStatus: String?,
        storedHash: String?,
        incomingDigest: JsonObject
    ): Boolean {
        if (localStatus == null) return false
        if (RnsOfferDigest.isTombstone(incomingDigest)) return false
        val incomingHash = incomingDigest["h"]?.jsonPrimitive?.content ?: return false
        if (storedHash == null) return false
        return storedHash != incomingHash
    }

    /**
     * Whether a terminal tombstone digest must be applied to our row.
     * Applies only when we actually hold the offer and it is not already
     * terminal. A tombstone never resurrects/creates a row.
     *
     * @param localStatus current stored status (null = no row ⇒ ignore)
     */
    fun acceptTombstone(localStatus: String?): Boolean {
        if (localStatus == null) return false
        val status = runCatching { OfferStatus.valueOf(localStatus) }.getOrNull() ?: return false
        return status != OfferStatus.COMPLETED && status != OfferStatus.CANCELLED
    }

    /**
     * Whether a terminal tombstone must DELETE our row instead of marking
     * it terminal. True for OBSERVER rows — the local identity is neither
     * the creator nor the matched peer — so a finished trade's offer
     * disappears from the feed entirely. Party rows (creator / matched
     * peer) are kept and marked COMPLETED: the buyer's escrow detail reads
     * fiat + bank details from the offer row, and the creator's row is
     * their own history.
     *
     * @param localStatus   current stored status (null = no row ⇒ ignore)
     * @param creatorPeerId the offer's creator peer id
     * @param matchedPeerId the offer's matched peer id (null when never matched)
     * @param myPeerId      the local identity's peer id
     */
    fun tombstoneDeletesRow(
        localStatus: String?,
        creatorPeerId: String?,
        matchedPeerId: String?,
        myPeerId: String
    ): Boolean {
        if (!acceptTombstone(localStatus)) return false
        if (myPeerId.isBlank()) return false
        if (creatorPeerId.equals(myPeerId, ignoreCase = true)) return false
        if (matchedPeerId?.equals(myPeerId, ignoreCase = true) == true) return false
        return true
    }
}
