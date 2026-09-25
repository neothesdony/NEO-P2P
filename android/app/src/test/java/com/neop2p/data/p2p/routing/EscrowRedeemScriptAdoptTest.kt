package com.neop2p.data.p2p.routing

import com.neop2p.data.escrow.EscrowScriptTemplate
import com.neop2p.data.escrow.EscrowScripts
import com.neop2p.domain.model.BitcoinAddressType
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowRedeemScriptAdoptTest {

    private val net = TestNet3Params.get()

    private data class Built(
        val hex: String,
        val address: String,
        val arbHex: String,
        val sellerHex: String,
        val lockSec: Long,
    )

    private fun build(template: EscrowScriptTemplate): Built {
        val buyer = ECKey(); val seller = ECKey(); val arb = ECKey()
        val locktime = 1_900_000_000L
        val script = EscrowScripts.build(template, buyer, seller, arb, locktime)
        return Built(
            script.getProgram().joinToString("") { "%02x".format(it) },
            EscrowScripts.address(script, BitcoinAddressType.LEGACY, net),
            arb.publicKeyAsHex,
            seller.publicKeyAsHex,
            locktime,
        )
    }

    @Test
    fun `a matching script is adopted when the local row has none`() {
        val b = build(EscrowScriptTemplate.MULTISIG_2OF3_V0)
        assertTrue(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = null, remoteScript = b.hex, fundingAddress = b.address,
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_V0,
                net = net, arbPubKeyHex = b.arbHex,
            )
        )
    }

    @Test
    fun `a mismatch with the funding address is refused`() {
        val b = build(EscrowScriptTemplate.MULTISIG_2OF3_V0)
        assertFalse(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = null, remoteScript = b.hex, fundingAddress = "tb1qwrongaddress",
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_V0,
                net = net, arbPubKeyHex = b.arbHex,
            )
        )
    }

    @Test
    fun `an already-set local script is never overwritten`() {
        val b = build(EscrowScriptTemplate.MULTISIG_2OF3_V0)
        assertFalse(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = "00", remoteScript = b.hex, fundingAddress = b.address,
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_V0,
                net = net, arbPubKeyHex = b.arbHex,
            )
        )
    }

    @Test
    fun `a script missing the expected arbitrator key is refused`() {
        val b = build(EscrowScriptTemplate.MULTISIG_2OF3_V0)
        val otherArb = ECKey().publicKeyAsHex
        assertFalse(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = null, remoteScript = b.hex, fundingAddress = b.address,
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_V0,
                net = net, arbPubKeyHex = otherArb,
            )
        )
    }

    @Test
    fun `a V1 script is adopted only with the seller key and a trusted floor`() {
        val b = build(EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1)
        val floor = b.lockSec * 1000L - EscrowScripts.MATURITY_MS
        assertTrue(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = null, remoteScript = b.hex, fundingAddress = b.address,
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1,
                net = net, arbPubKeyHex = b.arbHex,
                expectedSellerPubKeyHex = b.sellerHex, maturityFloorMs = floor,
            )
        )
        // A floor later than the locktime rejects the script.
        assertFalse(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = null, remoteScript = b.hex, fundingAddress = b.address,
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1,
                net = net, arbPubKeyHex = b.arbHex,
                expectedSellerPubKeyHex = b.sellerHex, maturityFloorMs = b.lockSec * 1000L + EscrowScripts.MATURITY_MS,
            )
        )
        // Missing anchor fails closed.
        assertFalse(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = null, remoteScript = b.hex, fundingAddress = b.address,
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1,
                net = net, arbPubKeyHex = b.arbHex,
                expectedSellerPubKeyHex = b.sellerHex, maturityFloorMs = null,
            )
        )
    }
}
