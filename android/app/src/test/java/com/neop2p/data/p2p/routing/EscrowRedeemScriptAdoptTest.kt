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

    private fun build(
        template: EscrowScriptTemplate,
    ): Triple<String, String, String> {
        val buyer = ECKey()
        val seller = ECKey()
        val arb = ECKey()
        val locktime = 1_900_000_000L
        val script = EscrowScripts.build(template, buyer, seller, arb, locktime)
        val address = EscrowScripts.address(script, BitcoinAddressType.LEGACY, net)
        return Triple(script.getProgram().joinToString("") { "%02x".format(it) }, address, arb.publicKeyAsHex)
    }

    @Test
    fun `a matching script is adopted when the local row has none`() {
        val (hex, address, arbHex) = build(EscrowScriptTemplate.MULTISIG_2OF3_V0)
        assertTrue(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = null, remoteScript = hex, fundingAddress = address,
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_V0,
                net = net, arbPubKeyHex = arbHex,
            )
        )
    }

    @Test
    fun `a mismatch with the funding address is refused`() {
        val (hex, _, arbHex) = build(EscrowScriptTemplate.MULTISIG_2OF3_V0)
        assertFalse(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = null, remoteScript = hex, fundingAddress = "tb1qwrongaddress",
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_V0,
                net = net, arbPubKeyHex = arbHex,
            )
        )
    }

    @Test
    fun `an already-set local script is never overwritten`() {
        val (hex, address, arbHex) = build(EscrowScriptTemplate.MULTISIG_2OF3_V0)
        assertFalse(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = "00", remoteScript = hex, fundingAddress = address,
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_V0,
                net = net, arbPubKeyHex = arbHex,
            )
        )
    }

    @Test
    fun `a script missing the expected arbitrator key is refused`() {
        val (hex, address, _) = build(EscrowScriptTemplate.MULTISIG_2OF3_V0)
        val otherArb = ECKey().publicKeyAsHex
        assertFalse(
            EscrowRouter.shouldAdoptRemoteRedeemScript(
                localScript = null, remoteScript = hex, fundingAddress = address,
                fundingScriptType = "LEGACY", expectedTemplate = EscrowScriptTemplate.MULTISIG_2OF3_V0,
                net = net, arbPubKeyHex = otherArb,
            )
        )
    }
}
