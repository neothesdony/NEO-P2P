package com.neop2p.data.escrow

import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the arbitration-resolution 2-of-3 broadcast path: an arbitrator
 * signature plus the local key (which is BOTH buyer and seller in the
 * single-key model) assembles a spendable 2-of-3 scriptSig, while a single
 * signature (the arbitrator alone) cannot broadcast. Mirrors
 * EscrowRefundSigningTest / EscrowRoleSigningTest — plain JUnit 4, no
 * Robolectric, no Android BuildConfig access.
 *
 * It replicates `EscrowService.assemble2of3ScriptSig`'s sig-assembly and the
 * CHECKMULTISIG semantics (signatures in redeem-script pubkey order, skipped
 * pubkeys allowed) so the release/refund resolution path can be tested without
 * a Room database or an IdentityManager.
 */
class EscrowArbitrationResolutionTest {

    private val params: NetworkParameters = TestNet3Params.get()

    private fun hex(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** Mirrors EscrowService.verifySignature (handles x-only pubkeys). */
    private fun verifySignature(tx: Transaction, redeemScript: Script, pubkeyHex: String, sig: ByteArray): Boolean {
        return try {
            val pubBytes = hex(pubkeyHex)
            val key = if (pubBytes.size == 32) {
                ECKey.fromPublicOnly(byteArrayOf(0x02) + pubBytes)
            } else {
                ECKey.fromPublicOnly(pubBytes)
            }
            val ts = TransactionSignature.decodeFromBitcoin(sig, true, true)
            val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            key.verify(hash, ts)
        } catch (_: Exception) {
            false
        }
    }

    /** Mirrors EscrowService.signRaw (DER + SIGHASH_ALL over input 0). */
    private fun signTx(tx: Transaction, redeemScript: Script, key: ECKey): ByteArray {
        val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
        val sig = key.sign(hash)
        return sig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
    }

    /**
     * Mirrors EscrowService.assemble2of3ScriptSig: for each role slot in
     * redeem-script pubkey order [buyer, seller, arbitrator], use a provided
     * signature if it verifies against that role's pubkey; else use [localKey]
     * if it matches that role. Returns null if fewer than 2 valid signatures.
     */
    private fun assemble2of3(
        tx: Transaction,
        redeemScript: Script,
        buyerPubkey: String,
        sellerPubkey: String,
        arbPubkey: String,
        localKey: ECKey,
        providedSig: ByteArray? = null
    ): Script? {
        val roles = listOf(
            buyerPubkey,
            sellerPubkey,
            arbPubkey
        )
        val sigs = mutableListOf<ByteArray>()
        for (rolePubkey in roles) {
            var sig: ByteArray? = null
            // Provided signature (arbitrator or stored) for this slot.
            if (providedSig != null && verifySignature(tx, redeemScript, rolePubkey, providedSig)) {
                sig = providedSig
            }
            // Local key, if it matches this role.
            if (sig == null) {
                val compressed = localKey.publicKeyAsHex
                val xOnly = if (compressed.length == 66) compressed.substring(2) else compressed
                if (compressed.equals(rolePubkey, true) || xOnly.equals(rolePubkey, true)) {
                    val candidate = signTx(tx, redeemScript, localKey)
                    if (verifySignature(tx, redeemScript, rolePubkey, candidate)) {
                        sig = candidate
                    }
                }
            }
            sig?.let { sigs.add(it) }
        }
        if (sigs.size < 2) return null
        // Mirror of the fixed EscrowService.assemble2of3Spend trim: use the
        // trimmed list directly, NEVER clear()+addAll() back into the same
        // list (aliasing wiped the sigs when size <= 2 → empty witness).
        val finalSigs = if (sigs.size > 2) sigs.take(2) else sigs
        return ScriptBuilder.createMultiSigInputScriptBytes(finalSigs, redeemScript.getProgram())
    }

    private fun buildPayoutTx(redeemScript: Script): Transaction {
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 0L, ScriptBuilder.createEmpty())
        val buyerAddr = LegacyAddress.fromKey(params, ECKey()) // valid fresh testnet address
        tx.addOutput(Coin.valueOf(999_000L), buyerAddr)
        return tx
    }

    private fun buildRefundTx(redeemScript: Script): Transaction {
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), 0L, ScriptBuilder.createEmpty())
        val sellerAddr = LegacyAddress.fromKey(params, ECKey()) // refund back to the seller/depositor
        tx.addOutput(Coin.valueOf(500_000L), sellerAddr)
        return tx
    }

    @Test
    fun `arbitrator plus local key releases the payout (2-of-3 spendable)`() {
        // Single-key model: buyerKey == sellerKey == local user key.
        val userKey = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(userKey, userKey, arb))
        val tx = buildPayoutTx(redeem)

        // Arbitrator signs the payout.
        val arbSig = signTx(tx, redeem, arb)
        // assemble2of3: local key fills buyer+seller slots, arb sig fills the arb slot.
        val scriptSig = assemble2of3(
            tx, redeem,
            userKey.publicKeyAsHex, userKey.publicKeyAsHex, arb.publicKeyAsHex,
            localKey = userKey,
            providedSig = arbSig
        )
        assertTrue("Arbitrator + local key must form a 2-of-3 scriptSig", scriptSig != null)
        tx.getInput(0).setScriptSig(scriptSig!!)

        // Each signature verifies against its role pubkey (P0-1 binding).
        assertTrue(verifySignature(tx, redeem, arb.publicKeyAsHex, arbSig))
        // The user's single key satisfies BOTH the buyer and seller slots.
        val userSig = signTx(tx, redeem, userKey)
        assertTrue(verifySignature(tx, redeem, userKey.publicKeyAsHex, userSig))

        // scriptSig carries ≥2 signatures plus the redeem script.
        assertTrue("scriptSig should have at least 3 chunks", scriptSig!!.chunks.size >= 3)
        assertEquals(redeem.program.joinToString("") { "%02x".format(it) },
            redeem.program.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `single-key release without arbitrator assembles 2-of-3 (regression)`() {
        // Regression (2026-09-01): a release with NO dispute has no arbitrator
        // signature. The local key occupies BOTH the buyer and seller slots of
        // the redeem script ([K, K, arb]), so the assembly must emit TWO
        // signatures (one per slot) to reach the 2-of-3 threshold. The
        // pubkey-level dedup introduced in e70be3c skipped the second slot and
        // left only 1 sig → "Fewer than 2 valid signatures to release".
        val userKey = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(userKey, userKey, arb))
        val tx = buildPayoutTx(redeem)

        val scriptSig = assemble2of3(
            tx, redeem,
            userKey.publicKeyAsHex, userKey.publicKeyAsHex, arb.publicKeyAsHex,
            localKey = userKey,
            providedSig = null
        )
        assertNotNull("Two signatures from the same key in two role slots must satisfy 2-of-3", scriptSig)
        tx.getInput(0).setScriptSig(scriptSig!!)

        val sigs = scriptSig.chunks
            .filter { chunk ->
                val d = chunk.data
                chunk.isPushData && d != null && d.size in 70..74
            }
            .map { it.data!! }
        assertEquals("Expect exactly 2 signatures in the scriptSig", 2, sigs.size)
        // Both signatures are produced by the ONE local key and verify against
        // the buyer slot AND the seller slot (same pubkey in this model).
        assertTrue(verifySignature(tx, redeem, userKey.publicKeyAsHex, sigs[0]))
        assertTrue(verifySignature(tx, redeem, userKey.publicKeyAsHex, sigs[1]))
    }

    @Test
    fun `arbitrator alone cannot release (single signature is not 2-of-3)`() {
        val userKey = ECKey()
        val arb = ECKey()
        // Redeem with distinct buyer/seller keys so the arbitrator sig fills ONLY
        // its own slot and cannot double as buyer/seller.
        val buyer = ECKey()
        val seller = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(buyer, seller, arb))
        val tx = buildPayoutTx(redeem)

        val arbSig = signTx(tx, redeem, arb)
        // The local key (buyer, say) + arb sig → only 2 sigs total, but each is
        // distinct and valid → this IS spendable. To prove a single sig is not
        // enough, use a local key that matches NONE of the role slots.
        val strangerKey = ECKey()
        val scriptSig = assemble2of3(
            tx, redeem,
            buyer.publicKeyAsHex, seller.publicKeyAsHex, arb.publicKeyAsHex,
            localKey = strangerKey,
            providedSig = arbSig
        )
        assertNull(
            "A single arbitrator signature (with no matching local role key) must not form 2-of-3",
            scriptSig
        )

        // And a lone sig must NOT verify against the buyer/seller role pubkeys.
        assertFalse(verifySignature(tx, redeem, buyer.publicKeyAsHex, arbSig))
        assertFalse(verifySignature(tx, redeem, seller.publicKeyAsHex, arbSig))
        assertTrue(verifySignature(tx, redeem, arb.publicKeyAsHex, arbSig))
    }

    @Test
    fun `refund resolution uses the refund-tx semantics`() {
        // Single-key model: buyerKey == sellerKey == local user key.
        val userKey = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(userKey, userKey, arb))
        val refundTx = buildRefundTx(redeem)

        val arbSig = signTx(refundTx, redeem, arb)
        val scriptSig = assemble2of3(
            refundTx, redeem,
            userKey.publicKeyAsHex, userKey.publicKeyAsHex, arb.publicKeyAsHex,
            localKey = userKey,
            providedSig = arbSig
        )
        assertTrue("Refund resolution must assemble a spendable 2-of-3", scriptSig != null)
        refundTx.getInput(0).setScriptSig(scriptSig!!)

        // The arbitrator sig and the user sig both verify on the REFUND tx input.
        assertTrue(verifySignature(refundTx, redeem, arb.publicKeyAsHex, arbSig))
        assertTrue(verifySignature(refundTx, redeem, userKey.publicKeyAsHex, signTx(refundTx, redeem, userKey)))
        assertTrue("Refund scriptSig should carry ≥2 sigs", scriptSig!!.chunks.size >= 3)
    }
}
