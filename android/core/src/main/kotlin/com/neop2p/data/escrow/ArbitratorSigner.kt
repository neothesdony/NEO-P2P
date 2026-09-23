package com.neop2p.data.escrow

import com.neop2p.NeoLog
import com.neop2p.NeoP2PConfig
import org.bitcoinj.base.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.script.Script

/**
 * The arbitrator's key capabilities, extracted from `EscrowService` (Phase 0b)
 * so the headless `:admind` daemon can verify and sign resolutions with no
 * Android/Room dependency.
 *
 * Both operations are fully parameterized: the private key is passed in, and
 * neither function touches the database or `IdentityManager`.
 */
object ArbitratorSigner {

    private const val TAG = "ArbitratorSigner"

    /**
     * Verify an arbitrator's DER + SIGHASH_ALL signature over input 0 of a
     * transaction against the configured arbitrator pubkey (2026-09-02).
     * Used by the resolution ingest path to reject forged resolutions BEFORE
     * marking the arbitrator's feed resolved. Mirrors the sanity check inside
     * [sign]. Returns false on any parse/verify failure.
     */
    fun verify(
        txHex: String?,
        redeemScriptHex: String,
        arbitratorSigHex: String,
        depositSats: Long? = null,
        fundingScriptType: String? = null
    ): Boolean {
        return try {
            if (txHex.isNullOrBlank() || arbitratorSigHex.isBlank()) return false
            val tx = EscrowCodec.parseTx(txHex)
            val redeemScript = Script(EscrowCodec.hexToBytes(redeemScriptHex))
            val witness = fundingScriptType?.equals("SEGWIT", ignoreCase = true) == true
            val pub = ECKey.fromPublicOnly(EscrowCodec.xOnlyToCompressed(NeoP2PConfig.ARBITRATOR_PUBKEY))
            val parsed = TransactionSignature.decodeFromBitcoin(EscrowCodec.hexToBytes(arbitratorSigHex), true, true)
            val hash = if (witness) {
                val deposit = depositSats ?: return false
                tx.hashForWitnessSignature(0, redeemScript, Coin.valueOf(deposit), Transaction.SigHash.ALL, false)
            } else {
                tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            }
            pub.verify(hash, parsed)
        } catch (e: Exception) {
            NeoLog.w(TAG, "Arbitrator signature verification failed: ${e.message}")
            false
        }
    }

    /**
     * The ARBITRATOR signs a transaction they do NOT hold locally (remote
     * arbitration): given the unsigned tx hex carried in the dispute event and
     * the escrow's redeem script, produce the DER + SIGHASH_ALL signature.
     * Returns failure if the key is not the configured arbitrator key.
     */
    fun sign(
        unsignedTxHex: String,
        redeemScriptHex: String,
        arbitratorPrivKey: ByteArray,
        depositSats: Long? = null,
        fundingScriptType: String? = null
    ): Result<String> {
        return try {
            val key = ECKey.fromPrivate(arbitratorPrivKey)
            if (NeoP2PConfig.ARBITRATOR_PUBKEY != key.publicKeyAsHex &&
                NeoP2PConfig.ARBITRATOR_PUBKEY != EscrowCodec.xOnlyOf(key.publicKeyAsHex)
            ) {
                return Result.failure(
                    SecurityException("Provided key is not the arbitrator key")
                )
            }
            val tx = EscrowCodec.parseTx(unsignedTxHex)
            val redeemScript = Script(EscrowCodec.hexToBytes(redeemScriptHex))
            // P2WSH disputes sign with the BIP-143 witness sighash, which
            // commits the input value (the escrow's deposit). Legacy disputes
            // keep the legacy sighash. Old dispute events (pre-deposit_sats)
            // are always treated as legacy — P2WSH events always carry the
            // deposit (published by the same app version that created them).
            val witness = fundingScriptType?.equals("SEGWIT", ignoreCase = true) == true
            val sig = if (witness) {
                val deposit = depositSats
                    ?: return Result.failure(
                        Exception("Missing deposit_sats for SegWit dispute")
                    )
                val txSig = tx.calculateWitnessSignature(
                    0, key, redeemScript,
                    Coin.valueOf(deposit),
                    Transaction.SigHash.ALL, false
                )
                txSig.encodeToBitcoin()
            } else {
                val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
                val legacySig = key.sign(hash)
                legacySig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
            }
            val sigHex = sig.joinToString("") { "%02x".format(it) }
            // Sanity-check: verify against the actual signing key (parity-normalized to even
            // via IdentityManager.arbitratorPrivEven, so xOnly 02 == true). Using the
            // signing key's compressed pubkey guarantees the check passes if the
            // sighash is correct (deposit/witness), independent of config parity.
            val pub = ECKey.fromPublicOnly(EscrowCodec.hexToBytes(key.publicKeyAsHex))
            val parsed = TransactionSignature.decodeFromBitcoin(EscrowCodec.hexToBytes(sigHex), true, true)
            val checkHash = if (witness) {
                val deposit = depositSats ?: 0L
                tx.hashForWitnessSignature(0, redeemScript, Coin.valueOf(deposit), Transaction.SigHash.ALL, false)
            } else {
                tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            }
            if (!pub.verify(checkHash, parsed)) {
                return Result.failure(Exception("Arbitrator signature failed verification"))
            }
            Result.success(sigHex)
        } catch (e: Exception) {
            NeoLog.w(TAG, "Arbitrator signing failed", e)
            Result.failure(e)
        }
    }
}
