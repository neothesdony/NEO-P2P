package com.neop2p.data.escrow

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction

/**
 * Destination/output guard for arbitration transactions (F2, 2026-09-12).
 *
 * The arbitrator signs a tx blob supplied by ONE disputing party, and a
 * hostile party can hand-assemble any tx the arbitrator signed — so the
 * ability to verify DESTINATIONS at both ends (signing + applying) is the
 * only terminal protection for the money path.
 *
 * Rules:
 *  - refund: every output pays the attested seller refund address; the
 *    payout must not be more than [RefundExpectation.feeCeilingSats] below
 *    the funded input (a hostile opener must not be able to burn the
 *    difference into the miner fee).
 *  - release: every output pays the attested buyer address, the fee wallet,
 *    or the seller refund address (overpayment excess); the buyer output
 *    must be at least the trade amount.
 */
object ResolutionGuard {
    data class Verdict(val ok: Boolean, val reason: String = "")

    data class RefundExpectation(
        val destinationAddress: String,
        val fundedInputSats: Long,
        val feeCeilingSats: Long,
    )

    data class ReleaseExpectation(
        val buyerAddress: String,
        val feeWalletAddress: String,
        val sellerRefundAddress: String?,
        val tradeSats: Long,
    )

    fun outputSummaries(tx: Transaction, net: NetworkParameters): List<String> =
        tx.outputs.map { out ->
            val addr = try {
                out.scriptPubKey.getToAddress(net).toString()
            } catch (_: Exception) {
                "<non-address>"
            }
            "$addr: ${out.value.value} sats"
        }

    fun validateRefund(tx: Transaction, net: NetworkParameters, e: RefundExpectation): Verdict {
        if (tx.outputs.isEmpty()) return Verdict(false, "refund tx has no outputs")
        var paid = 0L
        for (out in tx.outputs) {
            val addr = try {
                out.scriptPubKey.getToAddress(net).toString()
            } catch (_: Exception) {
                return Verdict(false, "refund output is not a standard address")
            }
            if (!addr.equals(e.destinationAddress, ignoreCase = true)) {
                return Verdict(false, "refund pays $addr — expected ${e.destinationAddress}")
            }
            paid += out.value.value
        }
        if (paid < e.fundedInputSats - e.feeCeilingSats) {
            return Verdict(false, "refund pays $paid sats — more than the fee ceiling below input ${e.fundedInputSats}")
        }
        return Verdict(true)
    }

    fun validateRelease(tx: Transaction, net: NetworkParameters, e: ReleaseExpectation): Verdict {
        if (tx.outputs.isEmpty()) return Verdict(false, "payout tx has no outputs")
        val allowed = buildSet {
            add(e.buyerAddress.lowercase())
            add(e.feeWalletAddress.lowercase())
            e.sellerRefundAddress?.takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
        }
        var buyerPaid = 0L
        for (out in tx.outputs) {
            val addr = try {
                out.scriptPubKey.getToAddress(net).toString()
            } catch (_: Exception) {
                return Verdict(false, "payout output is not a standard address")
            }
            if (addr.lowercase() !in allowed) {
                return Verdict(false, "payout pays $addr — not an expected destination")
            }
            if (addr.equals(e.buyerAddress, ignoreCase = true)) buyerPaid += out.value.value
        }
        if (buyerPaid < e.tradeSats) {
            return Verdict(false, "buyer receives $buyerPaid sats — below trade amount ${e.tradeSats}")
        }
        return Verdict(true)
    }
}
