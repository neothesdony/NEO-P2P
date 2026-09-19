package com.neop2p.data.p2p

import network.reticulum.identity.Identity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * F1 (2026-09-12): peerId <-> RNS identity binding announce + spoof guard.
 *
 * The binding is over the RNS **identity hash** (stable across destinations);
 * the delivery destination is learned separately from the identity-bearing
 * `lxmf.delivery` announce. The fork skips announces for local destinations,
 * so these tests feed the two internal handlers directly (same seam as
 * [RnsSessionTest]). The handlers are pure — no Reticulum runtime is started.
 */
class RnsSessionBindingTest {

    private lateinit var configDir: java.io.File
    private lateinit var session: RnsSession

    @Before
    fun setUp() {
        configDir = Files.createTempDirectory("rns-binding-test-").toFile()
        session = RnsSession(
            configDir = configDir.absolutePath,
            seed = ByteArray(64) { (it + 3).toByte() },
            myPeerId = "12D3KooWBindingSelf",
        )
    }

    @After
    fun tearDown() {
        runCatching { session.stop() }
        configDir.deleteRecursively()
    }

    private fun peerIdFor(priv: ByteArray): String =
        KeyDerivation.deriveLibp2pPeerIdFromPublicKey(KeyDerivation.ed25519Public(priv))

    private fun pubHex(priv: ByteArray): String = KeyDerivation.ed25519Public(priv).toHex()

    private fun identity(seedByte: Int): Identity =
        Identity.fromPrivateKey(KeyDerivation.rnsIdentity(ByteArray(64) { (it + seedByte).toByte() }))

    private fun destHash(seedByte: Int): ByteArray = ByteArray(32) { (it + seedByte).toByte() }

    private fun packBinding(peerId: String, pubHex: String, sigHex: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        MessagePack.newDefaultPacker(buffer).use { packer ->
            packer.packArrayHeader(3)
            packer.packString(peerId)
            packer.packString(pubHex)
            packer.packString(sigHex)
        }
        return buffer.toByteArray()
    }

    /** appData as `announceIdentityBinding` would build it for [destHex]. */
    private fun bindingAppData(priv: ByteArray, peerId: String, identity: Identity, destHex: String): ByteArray {
        val sig = PeerBinding.sign(priv, PeerBinding.message(peerId, identity.hexHash, destHex))
        return packBinding(peerId, pubHex(priv), sig)
    }

    /** lxmf.delivery announce appData: msgpack `[displayName, stampCost]`. */
    private fun packDeliveryAnnounce(displayName: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        MessagePack.newDefaultPacker(buffer).use { packer ->
            packer.packArrayHeader(2)
            val nameBytes = displayName.toByteArray(Charsets.UTF_8)
            packer.packBinaryHeader(nameBytes.size)
            packer.writePayload(nameBytes)
            packer.packNil()
        }
        return buffer.toByteArray()
    }

    @Test
    fun `valid binding registers and verifies the sender delivery dest`() {
        val priv = ByteArray(32) { (it + 1).toByte() }
        val peerId = peerIdFor(priv)
        val peerIdentity = identity(11)
        val deliveryDest = destHash(40)
        val deliveryDestHex = deliveryDest.toHex()

        // 1. Delivery announce maps peerId -> delivery dest + identity hash.
        session.handlePeerAnnounce(deliveryDest, peerIdentity, packDeliveryAnnounce(peerId))
        // 2. Identity binding ties peerId -> RNS identity hash.
        val identityDest = destHash(90)
        session.handleIdentityAnnounce(
            identityDest, peerIdentity,
            bindingAppData(priv, peerId, peerIdentity, identityDest.toHex())
        )

        assertEquals(peerIdentity.hexHash, session.verifiedDestFor(peerId))
        assertEquals(deliveryDestHex, session.destHashOf(peerId))
        assertTrue("verified sender from the mapped delivery dest", session.isVerifiedSender(peerId, deliveryDestHex))
        assertFalse(
            "a different (unmapped) dest must not pass",
            session.isVerifiedSender(peerId, destHash(200).toHex())
        )
    }

    @Test
    fun `spoofed binding signed by attacker key is rejected`() {
        val victimPriv = ByteArray(32) { (it + 2).toByte() }
        val victimPeerId = peerIdFor(victimPriv)
        val attackerPriv = ByteArray(32) { (it + 9).toByte() }
        val peerIdentity = identity(13)
        val identityDest = destHash(70)
        val identityDestHex = identityDest.toHex()
        // Attacker signs the victim's claim with the attacker key (and publishes
        // the attacker pubkey) — the peerId/pubkey/signature triple is incoherent.
        val sig = PeerBinding.sign(attackerPriv, PeerBinding.message(victimPeerId, peerIdentity.hexHash, identityDestHex))
        val appData = packBinding(victimPeerId, pubHex(attackerPriv), sig)

        session.handleIdentityAnnounce(identityDest, peerIdentity, appData)

        assertFalse(session.isVerifiedSender(victimPeerId, identityDestHex))
        assertNull(session.verifiedDestFor(victimPeerId))
    }

    @Test
    fun `unverified delivery announce from a different identity cannot rebind a verified peerId`() {
        val priv = ByteArray(32) { (it + 4).toByte() }
        val peerId = peerIdFor(priv)
        val realIdentity = identity(17)
        val deliveryDest = destHash(100)
        val deliveryDestHex = deliveryDest.toHex()
        // Legitimate mapping + verified binding.
        session.handlePeerAnnounce(deliveryDest, realIdentity, packDeliveryAnnounce(peerId))
        session.handleIdentityAnnounce(
            destHash(110), realIdentity,
            bindingAppData(priv, peerId, realIdentity, destHash(110).toHex())
        )
        assertEquals(deliveryDestHex, session.destHashOf(peerId))

        // A DIFFERENT identity claims the same peerId from dest X — must be
        // ignored because the verified binding pins peerId to realIdentity.
        val spoofDest = destHash(150)
        val spoofIdentity = identity(200)
        session.handlePeerAnnounce(spoofDest, spoofIdentity, packDeliveryAnnounce(peerId))

        assertEquals(
            "verified delivery dest must survive a spoofed delivery announce",
            deliveryDestHex, session.destHashOf(peerId)
        )
        assertEquals(peerId, session.peerIdOfDestHash(deliveryDestHex))
        assertNull("spoofed dest must not be mapped", session.peerIdOfDestHash(spoofDest.toHex()))
    }

    @Test
    fun `identity-less delivery announce cannot hijack a bound peerId`() {
        val priv = ByteArray(32) { (it + 5).toByte() }
        val peerId = peerIdFor(priv)
        val realIdentity = identity(19)
        val deliveryDest = destHash(120)
        val deliveryDestHex = deliveryDest.toHex()
        // Legitimate mapping + verified binding.
        session.handlePeerAnnounce(deliveryDest, realIdentity, packDeliveryAnnounce(peerId))
        session.handleIdentityAnnounce(
            destHash(130), realIdentity,
            bindingAppData(priv, peerId, realIdentity, destHash(130).toHex())
        )
        assertEquals(deliveryDestHex, session.destHashOf(peerId))

        // Another dest claims the same peerId with NO announced identity —
        // the old guard let this through and hijacked the routing maps.
        val spoofDest = destHash(160)
        session.handlePeerAnnounce(spoofDest, null, packDeliveryAnnounce(peerId))

        assertEquals(
            "identity-less announce must not rebind a verified delivery dest",
            deliveryDestHex, session.destHashOf(peerId)
        )
        assertNull(
            "identity-less announce must not be mapped",
            session.peerIdOfDestHash(spoofDest.toHex())
        )
    }
}
