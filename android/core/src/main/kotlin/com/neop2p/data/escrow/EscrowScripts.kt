package com.neop2p.data.escrow

import com.neop2p.domain.model.BitcoinAddressType
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.internal.CryptoUtils
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes

object EscrowScripts {

    /** 30 days: comfortably past every dispute/payment window. */
    const val MATURITY_MS: Long = 30L * 24 * 60 * 60 * 1000

    fun locktimeFor(createdAtMs: Long): Long = (createdAtMs + MATURITY_MS) / 1000

    fun build(
        template: EscrowScriptTemplate,
        buyerKey: ECKey,
        sellerKey: ECKey,
        arbKey: ECKey,
        cltvLocktime: Long,
    ): Script = when (template) {
        EscrowScriptTemplate.MULTISIG_2OF3_V0 ->
            ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey))
        EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1 ->
            // CHECKMULTISIG requires signatures in ascending script-pubkey
            // order; assemble2of3Spend sorts signatures by compressed pubkey,
            // so the script itself must be sorted too (V0 gets this free from
            // ScriptBuilder.createRedeemScript). Without it a 2-of-3 spend
            // fails whenever the arb key sorts before a role key.
            {
                val ordered = listOf(buyerKey, sellerKey, arbKey).sortedWith(ECKey.PUBKEY_COMPARATOR)
                ScriptBuilder()
                    .op(ScriptOpCodes.OP_IF)
                    .number(cltvLocktime)
                    .op(ScriptOpCodes.OP_CHECKLOCKTIMEVERIFY)
                    .op(ScriptOpCodes.OP_DROP)
                    .data(sellerKey.pubKey)
                    .op(ScriptOpCodes.OP_CHECKSIG)
                    .op(ScriptOpCodes.OP_ELSE)
                    .number(2)
                    .data(ordered[0].pubKey)
                    .data(ordered[1].pubKey)
                    .data(ordered[2].pubKey)
                    .number(3)
                    .op(ScriptOpCodes.OP_CHECKMULTISIG)
                    .op(ScriptOpCodes.OP_ENDIF)
                    .build()
            }
    }

    fun address(script: Script, type: BitcoinAddressType, net: NetworkParameters): String {
        val program = script.getProgram()
        return when (type) {
            BitcoinAddressType.LEGACY ->
                LegacyAddress.fromScriptHash(net, CryptoUtils.sha256hash160(program)).toBase58()
            BitcoinAddressType.SEGWIT ->
                SegwitAddress.fromProgram(net, 0, Sha256Hash.hash(program)).toBech32()
        }
    }
}
