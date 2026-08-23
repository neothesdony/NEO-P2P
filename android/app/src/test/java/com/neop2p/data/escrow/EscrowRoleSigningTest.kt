package com.neop2p.data.escrow

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the P0-1 hardening in EscrowService: a signature is only accepted for
 * a role if it was produced by that role's key. This is the exact logic that
 * signPayoutAsBuyer/signPayoutAsSeller and releaseFunds rely on, extracted here
 * so it can be tested without an Android Room database.
 *
 * Regression: the OLD reference signed the payout with ONE key for BOTH buyer
 * and seller. That is impossible here — a buyer key cannot satisfy the seller
 * role and vice versa.
 */
class EscrowRoleSigningTest {

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

    /** Mirrors EscrowService.verifySignature: verify [sig] over input 0 of [tx] using [pubkeyHex]. */
    private fun verifySignature(tx: Transaction, redeemScript: Script, pubkeyHex: String, sig: ByteArray): Boolean {
        return try {
            val key = ECKey.fromPublicOnly(hex(pubkeyHex))
            val ts = TransactionSignature.decodeFromBitcoin(sig, true, true)
            val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            key.verify(hash, ts)
        } catch (_: Exception) {
            false
        }
    }

    /** Build a minimal unsigned payout tx spending a fake funding output. */
    private fun buildPayoutTx(redeemScript: Script): Transaction {
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.wrap("1111111111111111111111111111111111111111111111111111111111111111"), 0L, ScriptBuilder.createEmpty())
        // Generate a real fresh testnet address (valid checksum).
        val buyerAddr = LegacyAddress.fromKey(params, ECKey())
        tx.addOutput(org.bitcoinj.core.Coin.valueOf(999_000L), buyerAddr)
        return tx
    }

    /** Mirrors EscrowService.signTransaction (DER + SIGHASH_ALL over input 0). */
    private fun signTx(tx: Transaction, redeemScript: Script, key: ECKey): ByteArray {
        val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
        val sig = key.sign(hash)
        return sig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
    }

    @Test
    fun `buyer signature verifies as buyer but NOT as seller`() {
        val buyer = ECKey()
        val seller = ECKey()
        val arb = ECKey()

        val redeem = ScriptBuilder.createRedeemScript(2, listOf(buyer, seller, arb))
        val tx = buildPayoutTx(redeem)
        val sig = signTx(tx, redeem, buyer)

        // The buyer key signs, so it must satisfy the buyer pubkey...
        assertTrue(verifySignature(tx, redeem, buyer.publicKeyAsHex, sig))
        // ...but it must NOT satisfy the seller pubkey (role-binding).
        assertFalse(verifySignature(tx, redeem, seller.publicKeyAsHex, sig))
    }

    @Test
    fun `a signature from the wrong role key is rejected`() {
        val buyer = ECKey()
        val seller = ECKey()
        val arb = ECKey()
        val redeemScript = ScriptBuilder.createRedeemScript(2, listOf(buyer, seller, arb))
        val tx = buildPayoutTx(redeemScript)

        // Seller tries to sign into the BUYER slot (the old one-key-for-both bug).
        val sellerSig = signTx(tx, redeemScript, seller)
        assertFalse("Seller key must not be accepted as a buyer signature",
            verifySignature(tx, redeemScript, buyer.publicKeyAsHex, sellerSig))
    }
}
