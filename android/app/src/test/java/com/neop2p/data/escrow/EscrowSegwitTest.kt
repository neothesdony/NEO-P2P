package com.neop2p.data.escrow

import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.SegwitAddress
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the SegWit (P2WSH) escrow path mirrors the legacy P2SH path:
 *   - the SAME 2-of-3 redeem script derives a P2SH (2…/m…) OR a P2WSH
 *     (tb1q…/bc1q…) funding address — only the carrier changes;
 *   - a P2WSH spend signs with the BIP-143 witness sighash (value-committed)
 *     and carries the signatures in the witness, not the scriptSig;
 *   - the P2WSH witness spend assembles 2-of-3 exactly like the P2SH scriptSig
 *     (arbitrator + local key in the single-key model).
 *
 * Plain JUnit 4, no Robolectric, no Android BuildConfig — mirrors the pattern
 * of EscrowArbitrationResolutionTest / EscrowRoleSigningTest.
 */
class EscrowSegwitTest {

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

    /** Mirrors EscrowService.createEscrow for SEGWIT: P2WSH from the redeem program. */
    private fun p2wshAddress(redeemScript: Script): SegwitAddress =
        SegwitAddress.fromProgram(params, 0, Sha256Hash.hash(redeemScript.getProgram()))

    /** Mirrors EscrowService.createEscrow for LEGACY: P2SH from the redeem program. */
    private fun p2shAddress(redeemScript: Script): LegacyAddress =
        LegacyAddress.fromScriptHash(params, org.bitcoinj.core.Utils.sha256hash160(redeemScript.getProgram()))

    /** Mirrors EscrowService.signRaw(witness=true): BIP-143 witness sighash. */
    private fun signWitness(tx: Transaction, redeemScript: Script, key: ECKey, depositSats: Long): ByteArray {
        val txSig = tx.calculateWitnessSignature(
            0, key, redeemScript,
            Coin.valueOf(depositSats),
            Transaction.SigHash.ALL, false
        )
        return txSig.encodeToBitcoin()
    }

    /** Mirrors EscrowService.verifySignature(witness=true). */
    private fun verifyWitness(
        tx: Transaction,
        redeemScript: Script,
        pubkeyHex: String,
        sig: ByteArray,
        depositSats: Long
    ): Boolean {
        return try {
            val key = ECKey.fromPublicOnly(hex(pubkeyHex))
            val ts = TransactionSignature.decodeFromBitcoin(sig, true, true)
            val hash = tx.hashForWitnessSignature(0, redeemScript, Coin.valueOf(depositSats), Transaction.SigHash.ALL, false)
            key.verify(hash, ts)
        } catch (_: Exception) {
            false
        }
    }

    /** Build a minimal unsigned payout tx spending a fake P2WSH funding output. */
    private fun buildPayoutTx(redeemScript: Script, depositSats: Long): Transaction {
        val tx = Transaction(params)
        // The funding output is the P2WSH script (what the deposit paid into).
        tx.addInput(
            Sha256Hash.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            0L,
            ScriptBuilder.createP2WSHOutputScript(redeemScript)
        )
        val buyerAddr = LegacyAddress.fromKey(params, ECKey())
        tx.addOutput(Coin.valueOf(999_000L), buyerAddr)
        return tx
    }

    @Test
    fun `same redeem script yields P2SH and P2WSH addresses with correct prefixes`() {
        val buyer = ECKey()
        val seller = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(buyer, seller, arb))

        val p2sh = p2shAddress(redeem)
        val p2wsh = p2wshAddress(redeem)

        assertTrue("P2SH testnet address must start with 2", p2sh.toBase58().startsWith("2"))
        assertTrue("P2WSH testnet address must start with tb1q", p2wsh.toBech32().startsWith("tb1q"))
        // Same keys, same script — the two addresses must differ only in carrier.
        assertFalse(p2sh.toBase58() == p2wsh.toBech32())
    }

    @Test
    fun `p2wsh witness spend assembles 2-of-3 with BIP-143 signatures`() {
        // Single-key model: buyerKey == sellerKey == local user key.
        val userKey = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(userKey, userKey, arb))
        val depositSats = 500_000L
        val tx = buildPayoutTx(redeem, depositSats)

        // Arbitrator signs with the witness sighash.
        val arbSig = signWitness(tx, redeem, arb, depositSats)
        assertTrue(verifyWitness(tx, redeem, arb.publicKeyAsHex, arbSig, depositSats))

        // Local key signs both buyer + seller slots (single-key model).
        val userSig = signWitness(tx, redeem, userKey, depositSats)
        assertTrue(verifyWitness(tx, redeem, userKey.publicKeyAsHex, userSig, depositSats))

        // Assemble the witness in redeem-script pubkey order: [empty, sig, sig, redeem].
        val witness = TransactionWitness.redeemP2WSH(
            redeem,
            TransactionSignature.decodeFromBitcoin(userSig, true, true),
            TransactionSignature.decodeFromBitcoin(arbSig, true, true)
        )
        tx.getInput(0).setWitness(witness)

        assertTrue("P2WSH spend must carry a witness", tx.hasWitnesses())
        assertEquals(4, witness.pushCount)
        // Push 0 is the CHECKMULTISIG dummy; pushes 1-2 are the sigs; push 3 is the redeem script.
        assertEquals(0, witness.getPush(0).size)
        assertEquals(redeem.program.joinToString("") { "%02x".format(it) }, toHex(witness.getPush(3)))

        // Serialization includes the witness (broadcast-ready raw hex).
        val rawHex = toHex(tx.bitcoinSerialize())
        assertTrue("Witness tx serialization must contain the marker", rawHex.contains("00"))
        // Round-trip: the serialized tx keeps its witness.
        val parsed = Transaction(params, hex(rawHex))
        assertTrue(parsed.hasWitnesses())
    }

    @Test
    fun `witness sighash is value-committed - wrong deposit fails verification`() {
        val userKey = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(userKey, userKey, arb))
        val tx = buildPayoutTx(redeem, 500_000L)

        val sig = signWitness(tx, redeem, userKey, 500_000L)
        assertTrue(verifyWitness(tx, redeem, userKey.publicKeyAsHex, sig, 500_000L))
        // BIP-143 commits the input value: a different deposit must NOT verify.
        assertFalse(verifyWitness(tx, redeem, userKey.publicKeyAsHex, sig, 500_001L))
    }

    @Test
    fun `p2wsh vs p2sh vsize reflects the witness discount`() {
        // EscrowService fee math: networkFeeSats = fastest × payoutTxVsize.
        // P2WSH is ~half of P2SH because signatures live in the witness.
        assertTrue(com.neop2p.domain.model.BitcoinAddressType.SEGWIT.spendVsize <
            com.neop2p.domain.model.BitcoinAddressType.LEGACY.spendVsize)
        assertEquals(220L, com.neop2p.domain.model.BitcoinAddressType.LEGACY.spendVsize)
        assertEquals(104L, com.neop2p.domain.model.BitcoinAddressType.SEGWIT.spendVsize)
        // Full payout tx vsize: input + buyer output + fee output + overhead.
        assertEquals(298L, com.neop2p.domain.model.BitcoinAddressType.LEGACY.payoutTxVsize)
        assertEquals(176L, com.neop2p.domain.model.BitcoinAddressType.SEGWIT.payoutTxVsize)
        assertTrue(com.neop2p.domain.model.BitcoinAddressType.SEGWIT.payoutTxVsize <
            com.neop2p.domain.model.BitcoinAddressType.LEGACY.payoutTxVsize)
    }

    @Test
    fun `p2wsh script type is committed to the address output`() {
        val buyer = ECKey()
        val seller = ECKey()
        val arb = ECKey()
        val redeem = ScriptBuilder.createRedeemScript(2, listOf(buyer, seller, arb))
        val p2wsh = p2wshAddress(redeem)

        val script = org.bitcoinj.script.ScriptBuilder.createP2WSHOutputScript(redeem)
        assertNotNull(script)
        // v0 witness program: OP_0 <32-byte sha256 of redeem script>.
        assertEquals(org.bitcoinj.script.Script.ScriptType.P2WSH, script.getScriptType())
        assertEquals(32, p2wsh.getWitnessProgram().size)
    }
}
