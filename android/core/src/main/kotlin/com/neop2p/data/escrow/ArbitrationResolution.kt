package com.neop2p.data.escrow

import com.neop2p.NeoP2PConfig
import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.domain.model.ResolutionDecision
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.script.Script

/**
 * The arbitrator's resolution pre-sign chain, extracted verbatim from
 * `DisputeFeedViewModel.resolve` (Phase 1c) so `:app` and the headless
 * `:admind` daemon share one implementation of the F2 gate that stands
 * between a hostile dispute opener and a 2-of-3 release.
 *
 * Order (must not change):
 *  1. [txToSign] selects the tx the decision rules on — the payout (`psbt_hex`)
 *     for RELEASE_TO_BUYER, the pre-built refund (`refund_tx_hex`) for
 *     REFUND_TO_SELLER. It NEVER crosses decisions.
 *  2. [preSignVerdict] refuses unless the destination is role-attested AND the
 *     tx passes [ResolutionGuard] AND the attested role key is anchored in the
 *     escrow redeem script.
 *  3. [sign] delegates to [ArbitratorSigner].
 *
 * [roleKeyInRedeemScript] is deliberately fail-OPEN on an unparseable script
 * (matching the app's defence-in-depth comment: the attestation gate in step 2
 * is primary). This differs from [EscrowScriptGate.containsKey], which is
 * fail-closed — do not substitute it here.
 */
object ArbitrationResolution {

    data class Verdict(val ok: Boolean, val reason: String = "")

    /** The exact unsigned tx a decision must sign. Never falls back across decisions. */
    fun txToSign(record: DisputeRecord, decision: ResolutionDecision): Result<String> =
        when (decision) {
            ResolutionDecision.RELEASE_TO_BUYER ->
                record.psbtHex?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("No unsigned payout tx in dispute"))
            // NEVER fall back to the payout tx: signing the payout as a "refund"
            // would pay the BUYER while the parties record REFUNDED — a
            // money-path inversion. A dispute opened from a payout state has no
            // refund tx by design, so refund is not arbitrable remotely.
            ResolutionDecision.REFUND_TO_SELLER ->
                record.refundTxHex?.let { Result.success(it) }
                    ?: Result.failure(
                        IllegalStateException("No unsigned refund tx in dispute — cannot rule a refund")
                    )
        }

    /**
     * Attestation -> [ResolutionGuard] -> role-key-in-script anchor, in the
     * app's order. Fails closed for legacy rows with no attestations.
     */
    fun preSignVerdict(
        record: DisputeRecord,
        decision: ResolutionDecision,
        txHex: String,
        net: NetworkParameters,
        fundedInputSats: Long,
    ): Verdict {
        val redeem = record.redeemScriptHex
        if (redeem.isNullOrBlank()) return Verdict(false, "No redeem script in dispute")
        val tx = EscrowCodec.parseTx(txHex)
        val guardVerdict: ResolutionGuard.Verdict
        val roleKey: String?
        when (decision) {
            ResolutionDecision.REFUND_TO_SELLER -> {
                val addr = record.sellerRefundAddress
                if (addr.isNullOrBlank() || record.sellerPubkeyHex.isNullOrBlank() ||
                    !RoleAddressAttestation.verify(
                        record.sellerPubkeyHex, RoleAddressAttestation.KIND_SELLER_REFUND,
                        record.escrowId, addr, record.sellerRefundAttestation.orEmpty()
                    )
                ) {
                    return Verdict(false, "Refund destination is not attested by the seller key — refusing to sign")
                }
                guardVerdict = ResolutionGuard.validateRefund(
                    tx, net,
                    ArbitrationFunding.refundExpectation(addr, fundedInputSats)
                )
                roleKey = record.sellerPubkeyHex
            }
            ResolutionDecision.RELEASE_TO_BUYER -> {
                val buyerAddr = record.buyerBtcAddress
                if (buyerAddr.isNullOrBlank() || record.buyerPubkeyHex.isNullOrBlank() ||
                    record.offerId.isNullOrBlank() ||
                    !RoleAddressAttestation.verify(
                        record.buyerPubkeyHex, RoleAddressAttestation.KIND_BUYER_PAYOUT,
                        record.offerId, buyerAddr, record.buyerAddressAttestation.orEmpty()
                    )
                ) {
                    return Verdict(false, "Payout destination is not attested by the buyer key — refusing to sign")
                }
                guardVerdict = ResolutionGuard.validateRelease(
                    tx, net,
                    ResolutionGuard.ReleaseExpectation(
                        buyerAddr, NeoP2PConfig.FEE_WALLET_ADDRESS, record.sellerRefundAddress, record.tradeSats ?: 0L
                    )
                )
                roleKey = record.buyerPubkeyHex
            }
        }
        if (!guardVerdict.ok) return Verdict(false, "Resolution blocked: ${guardVerdict.reason}")
        if (roleKey.isNullOrBlank() || !roleKeyInRedeemScript(redeem, roleKey)) {
            return Verdict(false, "Role key is not a key of the escrow redeem script — refusing to sign")
        }
        return Verdict(true)
    }

    /** Signs [txHex] with the arbitrator key via [ArbitratorSigner]. */
    fun sign(
        record: DisputeRecord,
        txHex: String,
        arbitratorPrivKeyHex: String,
    ): Result<String> {
        val redeem = record.redeemScriptHex
            ?: return Result.failure(IllegalStateException("No redeem script in dispute"))
        return ArbitratorSigner.sign(
            txHex, redeem, arbitratorPrivKeyHex,
            depositSats = record.depositSats,
            fundingScriptType = record.fundingScriptType
        )
    }

    /**
     * Resolution delivery targets. The arbitrator has NO local escrow row, so
     * these are the parties carried by the dispute event only.
     */
    fun targets(record: DisputeRecord): List<String> =
        listOfNotNull(record.buyerPeerId, record.sellerPeerId).distinct()

    fun outputSummaries(txHex: String, net: NetworkParameters): List<String> =
        ResolutionGuard.outputSummaries(EscrowCodec.parseTx(txHex), net)

    /** F2 defence in depth; unparseable script -> true (fail-open, see class doc). */
    fun roleKeyInRedeemScript(redeemScriptHex: String, roleKeyHex: String): Boolean = try {
        Script(EscrowCodec.hexToBytes(redeemScriptHex)).pubKeys.any { xOnly(it.publicKeyAsHex) == xOnly(roleKeyHex) }
    } catch (_: Exception) {
        true
    }

    /** x-only form — accepts compressed (33B), uncompressed (65B) or x-only (32B) keys. */
    private fun xOnly(pubHex: String): String {
        val bytes = EscrowCodec.hexToBytes(pubHex)
        val x = when (bytes.size) {
            33, 65 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> bytes
        }
        return x.joinToString("") { "%02x".format(it) }
    }
}
