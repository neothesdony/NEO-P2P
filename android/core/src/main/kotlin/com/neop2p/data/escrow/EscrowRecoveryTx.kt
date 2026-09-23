package com.neop2p.data.escrow

import org.bitcoinj.base.Address
import org.bitcoinj.base.Coin
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder

/**
 * C9 (Phase 1): the seller's CHECKLOCKTIMEVERIFY recovery spend of a V1
 * (`MULTISIG_2OF3_CLTV_V1`) escrow output once the redeem script's maturity
 * has passed. The `OP_IF` branch needs only the seller key — no arbitrator —
 * so a stalled trade cannot strand the deposit forever.
 *
 * This builds and signs the transaction only. Broadcasting and UI wiring live
 * in the app layer.
 */
object EscrowRecoveryTx {

    /** Non-final so nLockTime is enforced; CLTV rejects a 0xffffffff sequence. */
    const val RECOVERY_SEQUENCE: Long = 0xfffffffeL

    /**
     * Build the single-output recovery transaction: the full on-chain value,
     * less a miner fee capped by [ArbitrationFunding.feeCeiling], back to the
     * seller's attested refund address.
     */
    fun build(
        fundingTxid: String,
        fundingVout: Long,
        fundedValueSats: Long,
        locktime: Long,
        sellerAddress: String,
        feeSats: Long,
        net: NetworkParameters
    ): Transaction {
        val ceiling = ArbitrationFunding.feeCeiling(fundedValueSats)
        require(feeSats in 0..ceiling) { "Recovery fee $feeSats outside [0, $ceiling]" }
        val outputSats = fundedValueSats - feeSats
        require(outputSats > 0) { "Recovery output $outputSats must be positive" }

        val tx = Transaction()
        val outPoint = TransactionOutPoint(fundingVout, Sha256Hash.wrap(fundingTxid))
        tx.addInput(
            TransactionInput(tx, ScriptBuilder.createEmpty().program, outPoint, RECOVERY_SEQUENCE)
        )
        tx.lockTime = locktime
        tx.addOutput(Coin.valueOf(outputSats), Address.fromString(net, sellerAddress))
        return tx
    }

    /**
     * Sign the recovery spend of input 0, selecting the `OP_IF` branch:
     * P2SH fills the scriptSig (`<sig> <1> <redeem>`), P2WSH fills the witness
     * (`[sig, 1, redeemScript]`). Returns null if the produced signature does
     * not verify against [sellerKey] — fail closed.
     */
    fun spendParts(
        tx: Transaction,
        redeemScript: Script,
        sellerKey: ECKey,
        fundedValueSats: Long,
        witness: Boolean
    ): SpendParts? {
        val sig = EscrowTxBuilder.signRaw(tx, redeemScript, sellerKey, fundedValueSats, witness)
        val verified = EscrowTxBuilder.verifySignature(
            tx, redeemScript, sellerKey.publicKeyAsHex, sig, fundedValueSats, witness
        )
        if (!verified) return null

        return if (witness) {
            SpendParts(
                witness = TransactionWitness.of(
                    sig,
                    byteArrayOf(0x01),
                    redeemScript.getProgram()
                )
            )
        } else {
            SpendParts(
                scriptSig = ScriptBuilder()
                    .data(sig)
                    .number(1)
                    .data(redeemScript.getProgram())
                    .build()
            )
        }
    }
}
