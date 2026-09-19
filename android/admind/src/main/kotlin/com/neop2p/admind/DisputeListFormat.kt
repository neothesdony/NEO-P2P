package com.neop2p.admind

import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.data.p2p.EvidenceRecord

/**
 * Pure rendering for `admind disputes`. Kept separate from the CLI so the
 * output shape is testable without an arbitrator identity or a live store.
 * Peer ids are truncated to a prefix; image bytes are never printed (only the
 * size + mime type).
 */
internal object DisputeListFormat {

    fun row(row: DisputeRecord, evidenceCount: Int): String =
        "${row.escrowId}  [${if (row.resolved) "resolved" else "OPEN"}]  " +
            "by=${row.openedBy.take(12)}…  opened_at=${row.openedAt}  evidence=$evidenceCount"

    fun detail(row: DisputeRecord, evidence: List<EvidenceRecord>): List<String> = buildList {
        add("  reason: ${row.reason}")
        add("  buyer: ${row.buyerPeerId?.take(12)}…  seller: ${row.sellerPeerId?.take(12)}…")
        add("  deposit_sats: ${row.depositSats}  trade_sats: ${row.tradeSats}  offer_id: ${row.offerId}")
        evidence.forEach { e ->
            add("  evidence ${e.evidenceId}: ${e.mimeType} ${e.imageData.size}B — ${e.description}")
        }
    }
}
