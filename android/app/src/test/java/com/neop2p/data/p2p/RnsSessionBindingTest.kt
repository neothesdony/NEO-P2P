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
 * The fork skips announces for local destinations, so these tests feed the two
 * internal handlers directly (same seam as [RnsSessionTest]). The handlers are
 * pure — no Reticulum runtime is started; the ctor is the minimal construction.
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
    fun `valid binding announce registers and verifies`() {
        val priv = ByteArray(32) { (it + 1).toByte() }
        val peerId = peerIdFor(priv)
        val identity = Identity.fromPrivateKey(KeyDerivation.rnsIdentity(ByteArray(64) { (it + 11).toByte() }))
        val destHex = destHash(40).toHex()

        session.handleIdentityAnnounce(destHash(40), identity, bindingAppData(priv, peerId, identity, destHex))

        assertTrue("valid binding must verify", session.isVerifiedSender(peerId, destHex))
        assertEquals(destHex, session.verifiedDestFor(peerId))
        assertEquals(destHex, session.destHashOf(peerId))
    }

    @Test
    fun `spoofed binding signed by attacker key is rejected`() {
        val victimPriv = ByteArray(32) { (it + 2).toByte() }
        val victimPeerId = peerIdFor(victimPriv)
        val attackerPriv = ByteArray(32) { (it + 9).toByte() }
        val identity = Identity.fromPrivateKey(KeyDerivation.rnsIdentity(ByteArray(64) { (it + 13).toByte() }))
        val destHex = destHash(70).toHex()
        // Attacker signs the victim's claim with the attacker key (and publishes
        // the attacker pubkey) — the peerId/pubkey/signature triple is incoherent.
        val sig = PeerBinding.sign(attackerPriv, PeerBinding.message(victimPeerId, identity.hexHash, destHex))
        val appData = packBinding(victimPeerId, pubHex(attackerPriv), sig)

        session.handleIdentityAnnounce(destHash(70), identity, appData)

        assertFalse(session.isVerifiedSender(victimPeerId, destHex))
        assertNull(session.verifiedDestFor(victimPeerId))
    }

    @Test
    fun `unverified delivery announce cannot rebind a verified peerId`() {
        val priv = ByteArray(32) { (it + 4).toByte() }
        val peerId = peerIdFor(priv)
        val identity = Identity.fromPrivateKey(KeyDerivation.rnsIdentity(ByteArray(64) { (it + 17).toByte() }))
        val verifiedDestHex = destHash(100).toHex()
        session.handleIdentityAnnounce(destHash(100), identity, bindingAppData(priv, peerId, identity, verifiedDestHex))
        assertEquals(verifiedDestHex, session.destHashOf(peerId))

        // An unverified lxmf.delivery announce claiming the same peerId from a
        // different destination X must NOT rebind it.
        val spoofDest = destHash(150)
        session.handlePeerAnnounce(spoofDest, identity, packDeliveryAnnounce(peerId))

        assertEquals(
            "verified destination must survive a spoofed delivery announce",
            verifiedDestHex, session.destHashOf(peerId)
        )
        assertTrue(session.isVerifiedSender(peerId, verifiedDestHex))
    }
}
