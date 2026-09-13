package com.neop2p.data.escrow

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction

/**
 * F-3 (2026-09-13): the ONLY pre-broadcast integrity gate for a payout.
 *
 * The stored `psbt_unsigned` is not trustworthy at broadcast time — a peer can overwrite
 * it over `escrow_status` (EscrowRouter). So every device re-derives the verdict from
 * data it already holds: the attested destination must be anchored in the already-funded
 * redeem script AND every output must pay the buyer, the fee wallet, or the seller's
 * refund address, with the buyer receiving at least the trade amount. Fails closed on a
 * missing attestation.
 */
object ReleaseIntegrity {
    data class Arguments(
        val buyerBtcAddress: String?,
        val buyerPubkeyHex: String?,
        val buyerAddressAttestation: String?,
        val offerId: String?,
        val redeemScriptHex: String?,
        val feeWalletAddress: String,
        val sellerRefundAddress: String?,
        val tradeSats: Long,
        val tx: Transaction,
        val net: NetworkParameters
    )

    fun verdict(args: Arguments): ResolutionGuard.Verdict {
        val anchored = ResolutionGuard.verifyBuyerPayoutDestination(
            buyerBtcAddress = args.buyerBtcAddress,
            buyerPubkeyHex = args.buyerPubkeyHex,
            buyerAddressAttestation = args.buyerAddressAttestation,
            offerId = args.offerId,
            redeemScriptHex = args.redeemScriptHex
        )
        if (!anchored.ok) return anchored
        return ResolutionGuard.validateRelease(
            args.tx,
            args.net,
            ResolutionGuard.ReleaseExpectation(
                buyerAddress = args.buyerBtcAddress!!,
                feeWalletAddress = args.feeWalletAddress,
                sellerRefundAddress = args.sellerRefundAddress,
                tradeSats = args.tradeSats
            )
        )
    }
}
