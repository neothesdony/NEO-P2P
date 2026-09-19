package com.neop2p.data.escrow

import com.neop2p.NeoLog
import com.neop2p.NeoP2PConfig
import com.neop2p.domain.model.BitcoinAddressType
import com.neop2p.domain.model.Escrow
import org.bitcoinj.base.Address
import org.bitcoinj.base.Coin
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder

/**
 * A 2-of-3 spend of input 0: P2SH fills [scriptSig], P2WSH fills [witness].
 * Moved top-level from `EscrowService` (Phase 0b) so both `:core` and `:app`
 * use one type.
 */
data class SpendParts(
    val scriptSig: Script? = null,
    val witness: TransactionWitness? = null
)

/**
 * The 2-of-3 multisig signing / tx-assembly primitives used by `EscrowService`,
 * extracted to `:core` (Phase 0b) so they can be tested without Room or an
 * `IdentityManager` — and reused by `:admind` for resolution work.
 *
 * Everything here is pure: the local private key and the `NetworkParameters`
 * are passed in. The caller (EscrowService) owns key access and DB persistence.
 */
object EscrowTxBuilder {

    private const val TAG = "EscrowTxBuilder"

    /**
     * The script type the escrow's funding output commits (P2SH vs P2WSH).
     *
     * C1d (2026-09-11): derived from the FUNDING ADDRESS ITSELF — the
     * deterministic address is the ground truth (created from the redeem
     * script), while the mutable `funding_script_type` field got corrupted by
     * the ingest feedback loop: the buyer's mirrored row pinned a stale
     * LEGACY, echoed it back, and flipped the seller's row too — so both
     * sides signed scriptSig/legacy SIGHASH over a P2WSH UTXO and every
     * broadcast died with "Witness requires empty scriptSig" (-26). The
     * address (tb1=SEGWIT, m/1=LEGACY) cannot lie.
     */
    fun escrowScriptType(
        fundingAddress: String?,
        fundingScriptType: String?,
        net: NetworkParameters
    ): BitcoinAddressType =
        try {
            val addr = Address.fromString(net, fundingAddress)
            when (addr) {
                is SegwitAddress -> BitcoinAddressType.SEGWIT
                else -> BitcoinAddressType.LEGACY
            }
        } catch (_: Exception) {
            // Fall back to the stored field when the address is unparseable.
            try {
                BitcoinAddressType.valueOf(fundingScriptType ?: "")
            } catch (_: Exception) {
                BitcoinAddressType.LEGACY
            }
        }

    /**
     * Verify that [signatureWithSighash] (DER + SIGHASH_ALL) was produced by
     * [pubkeyHex] over input 0 of [tx] spend using [redeemScript].
     *
     * P2WSH escrows verify with the BIP-143 witness sighash ([witness] =
     * true), which commits the input value ([depositSats]) — pass the escrow's
     * stored deposit.
     */
    fun verifySignature(
        tx: Transaction,
        redeemScript: Script,
        pubkeyHex: String,
        signatureWithSighash: ByteArray,
        depositSats: Long,
        witness: Boolean
    ): Boolean {
        return try {
            // Arbitrator pubkey is stored x-only; convert to compressed for bitcoinj.
            val pubBytes = EscrowCodec.hexToBytes(pubkeyHex)
            val key = if (pubBytes.size == 32) {
                ECKey.fromPublicOnly(EscrowCodec.xOnlyToCompressed(pubkeyHex))
            } else {
                ECKey.fromPublicOnly(pubBytes)
            }
            val sig = TransactionSignature.decodeFromBitcoin(signatureWithSighash, true, true)
            val hash = if (witness) {
                tx.hashForWitnessSignature(0, redeemScript, Coin.valueOf(depositSats), Transaction.SigHash.ALL, false)
            } else {
                tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            }
            key.verify(hash, sig)
        } catch (e: Exception) {
            NeoLog.w(TAG, "Signature verification failed: ${e.message}")
            false
        }
    }

    /**
     * Attach the 2-of-3 signatures to input 0 of [tx] for broadcast: P2SH
     * escrows get a scriptSig (`OP_0 sig sig redeem`), P2WSH escrows get the
     * witness (`[empty] sig sig redeem`).
     */
    fun attachSpend(tx: Transaction, spend: SpendParts) {
        spend.witness?.let { tx.replaceInput(0, tx.getInput(0).withWitness(it)) }
        spend.scriptSig?.let { tx.replaceInput(0, tx.getInput(0).withScriptSig(it)) }
    }

    /** Compare a key's pubkey (compressed hex or x-only hex) against a stored hex. */
    fun pubkey(key: ECKey, expectedHex: String): Boolean {
        val compressed = key.publicKeyAsHex
        return compressed.equals(expectedHex, ignoreCase = true) ||
            EscrowCodec.xOnlyOf(compressed).equals(expectedHex, ignoreCase = true)
    }

    /**
     * Sign input 0 of [tx] against [redeemScript]. Legacy escrows use the
     * legacy sighash (DER + SIGHASH_ALL); SegWit escrows use the BIP-143
     * witness sighash, which commits [depositSats] (the input value, stored
     * on the escrow at creation).
     */
    fun signRaw(
        tx: Transaction,
        redeemScript: Script,
        key: ECKey,
        depositSats: Long,
        witness: Boolean
    ): ByteArray {
        if (witness) {
            val txSig = tx.calculateWitnessSignature(
                0, key, redeemScript,
                Coin.valueOf(depositSats),
                Transaction.SigHash.ALL, false
            )
            return txSig.encodeToBitcoin()
        }
        val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
        val sig = key.sign(hash)
        return sig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
    }

    /**
     * Assemble a 2-of-3 spend of input 0 of [tx] — P2SH scriptSig for legacy
     * escrows, P2WSH witness for SegWit escrows — filling signatures in
     * redeem-script pubkey order [buyer, seller, arbitrator].
     *
     * Signature source per slot, in order of preference:
     *   1. a stored signature for that slot (escrow.buyerSignature /
     *      escrow.sellerSignature / [arbitratorSigHex]) IF it verifies via
     *      [verifySignature] against that role's pubkey;
     *   2. else the local key ([localPrivHex]) IF `pubkey(localKey, rolePubkey)`
     *      matches that role, signing via [signRaw] and verifying.
     *
     * CHECKMULTISIG semantics: signatures must appear in ascending redeem-script
     * pubkey order, but pubkeys WITHOUT a matching sig are skipped — so a valid
     * spend can be [buyerSig, sellerSig], [buyerSig, arbSig], [sellerSig,
     * arbSig], or all three. A signature only counts for a slot if it verifies
     * against that slot's pubkey (P0-1 role binding).
     *
     * @return a [SpendParts] (scriptSig/witness + attached tx), or null if
     * fewer than 2 valid distinct signatures can be produced for the 2-of-3.
     */
    fun assemble2of3Spend(
        tx: Transaction,
        redeemScript: Script,
        escrow: Escrow,
        localPrivHex: String,
        arbitratorSigHex: String? = null,
        net: NetworkParameters
    ): SpendParts? {
        val localKey = ECKey.fromPrivate(EscrowCodec.hexToBytes(localPrivHex))
        // BIP-143 commits the INPUT VALUE — the actual on-chain funding output
        // (2026-09-04), which may exceed the deposit on overpayment.
        val depositSats = escrow.fundedAmountSats ?: escrow.depositAmountSats
        val scriptType = escrowScriptType(escrow.fundingAddress, escrow.fundingScriptType.name, net)
        val witness = scriptType == BitcoinAddressType.SEGWIT

        // Role slots with their pubkey and a candidate signature.
        val roles = listOf(
            // (rolePubkey, storedSignature)
            escrow.buyerPubKeyHex to escrow.buyerSignature,
            escrow.sellerPubKeyHex to escrow.sellerSignature,
            NeoP2PConfig.ARBITRATOR_PUBKEY to arbitratorSigHex?.let { EscrowCodec.hexToBytes(it) }
        )

        // Collect one valid signature PER slot (in the single-key model the
        // SAME pubkey occupies both buyer and seller slots and must contribute
        // ONE signature per slot — CHECKMULTISIG evaluates each sig against its
        // own slot's pubkey, so two slots with one key need two sigs).
        val sigByRole = mutableListOf<Pair<String, ByteArray>>()
        for ((rolePubkey, storedSig) in roles) {
            if (rolePubkey == null) continue
            var sig: ByteArray? = null
            // 1) Stored signature for this slot, if it verifies.
            storedSig?.let {
                val ok = verifySignature(tx, redeemScript, rolePubkey, it, depositSats, witness)
                NeoLog.i(TAG, "verify stored sig for role ${rolePubkey.take(10)} witness=$witness deposit=$depositSats ok=$ok sigLen=${it.size}")
                if (ok) sig = it else NeoLog.w(TAG, "Stored sig failed verify for role ${rolePubkey.take(10)}")
            }
            // 2) Local key, if it matches this role.
            if (sig == null && pubkey(localKey, rolePubkey)) {
                val candidate = signRaw(tx, redeemScript, localKey, depositSats, witness)
                val ok2 = verifySignature(tx, redeemScript, rolePubkey, candidate, depositSats, witness)
                NeoLog.i(TAG, "verify local sig for role ${rolePubkey.take(10)} ok=$ok2 localPub=${localKey.publicKeyAsHex.take(10)}")
                if (ok2) sig = candidate else NeoLog.w(TAG, "Local sig failed verify for role ${rolePubkey.take(10)}")
            } else if (sig == null) {
                NeoLog.i(TAG, "No stored sig and localKey ${localKey.publicKeyAsHex.take(10)} != role ${rolePubkey.take(10)} xOnly=${EscrowCodec.xOnlyOf(localKey.publicKeyAsHex).take(10)}")
            }
            sig?.let { sigByRole.add(rolePubkey to it) }
        }

        // CHECKMULTISIG semantics: signatures must appear in ascending
        // redeem-script pubkey order, and createRedeemScript SORTS the pubkeys
        // (ECKey.PUBKEY_COMPARATOR, ascending bytes). Emit the collected
        // signatures in that sorted order — emitting them in a fixed
        // [buyer, seller, arb] order made CHECKMULTISIG match a signature
        // against the WRONG slot's pubkey and reject the spend
        // ("Signature must be zero for failed CHECK(MULTI)SIG operation").
        // The arbitrator's pubkey is compared in its COMPRESSED form
        // (xOnlyToCompressed), matching exactly what createRedeemScript sorted.
        val sigsInPubkeyOrder = sigByRole
            .sortedWith { a, b ->
                // Match createRedeemScript's sort exactly: it sorts on the
                // COMPRESSED pubkey bytes that went into the script. Buyer and
                // seller are stored compressed; the arbitrator is stored x-only
                // and was compressed (xOnlyToCompressed) when building the script.
                val ap = if (a.first == NeoP2PConfig.ARBITRATOR_PUBKEY)
                    EscrowCodec.xOnlyToCompressed(a.first) else EscrowCodec.hexToBytes(a.first)
                val bp = if (b.first == NeoP2PConfig.ARBITRATOR_PUBKEY)
                    EscrowCodec.xOnlyToCompressed(b.first) else EscrowCodec.hexToBytes(b.first)
                EscrowCodec.compareBytes(ap, bp)
            }
            .map { it.second }
            .toMutableList()

        NeoLog.i(TAG, "assemble2of3: collected ${sigsInPubkeyOrder.size} sigs need 2, roles=${sigByRole.map { it.first.take(10) }} redeem=${redeemScript.getProgram().joinToString("") { "%02x".format(it) }.take(120)}...")

        if (sigsInPubkeyOrder.size < 2) {
            NeoLog.w(TAG, "Cannot assemble 2-of-3 for escrow ${escrow.escrowId} deposit=$depositSats witness=$witness tx=${tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }.take(60)}...")
            return null
        }
        // For 2-of-3, keep exactly 2 signatures in pubkey order (already
        // ordered). NOTE: never clear()+addAll() back into the SAME list —
        // when size <= 2 the trimmed list IS the original, so the clear
        // destroys the collected signatures and the witness goes out empty
        // ("Operation not valid with the current stack size" on broadcast).
        val finalSigs = if (sigsInPubkeyOrder.size > 2) {
            sigsInPubkeyOrder.take(2)
        } else {
            sigsInPubkeyOrder
        }

        return when (scriptType) {
            BitcoinAddressType.LEGACY -> SpendParts(
                scriptSig = ScriptBuilder.createMultiSigInputScriptBytes(
                    finalSigs,
                    redeemScript.getProgram()
                )
            )
            BitcoinAddressType.SEGWIT -> {
                val sigs = finalSigs.map {
                    TransactionSignature.decodeFromBitcoin(it, true, true)
                }.toTypedArray()
                val witness = TransactionWitness.redeemP2WSH(redeemScript, *sigs)
                SpendParts(witness = witness)
            }
        }
    }
}
