package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Full two-party simulation: bundle exchange -> X3DH -> ratchet_init ->
 * bidirectional chat with interleaving, out-of-order delivery, replay, and a
 * DH ratchet step. Pure JVM; no Android.
 */
class DoubleRatchetEndToEndTest {

    private class Party(val peerId: String, val ikPriv: ByteArray, val ikPub: ByteArray) {
        var spkPriv = ByteArray(0)
        var spkPub = ByteArray(0)
        var state: RatchetState? = null
        var pendingInit: ByteArray? = null
    }

    private fun party(peerId: String, seed: Byte): Party {
        val ikPriv = ByteArray(32) { seed }
        val ikPub = X25519Public(ikPriv)
        return Party(peerId, ikPriv, ikPub)
    }

    private fun X25519Public(priv: ByteArray): ByteArray =
        org.bouncycastle.crypto.params.X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    private fun makeBundle(p: Party): RatchetPreKeyBundle {
        val (spkPriv, spkPub) = DoubleRatchet.generateDhKeyPair()
        p.spkPriv = spkPriv
        p.spkPub = spkPub
        return RatchetPreKeyBundle(
            ikPub = p.ikPub,
            spkPub = spkPub,
            spkSignature = ByteArray(64),
            identityPubKey = ByteArray(32),
            identitySignature = ByteArray(64),
        )
    }

    /**
     * X3DH with the same canonical ordering the app uses: the two
     * role-dependent DH outputs are sorted so both peers derive one secret.
     */
    private fun x3dh(me: Party, remote: RatchetPreKeyBundle): ByteArray {
        val dhIk = DoubleRatchet.dh(me.ikPriv, remote.spkPub)
        val dhSpk = DoubleRatchet.dh(me.spkPriv, remote.ikPub)
        val dh3 = DoubleRatchet.dh(me.spkPriv, remote.spkPub)
        val first: ByteArray
        val second: ByteArray
        if (compareBytes(dhIk, dhSpk) <= 0) {
            first = dhIk; second = dhSpk
        } else {
            first = dhSpk; second = dhIk
        }
        return RatchetKdf.x3dhSecret(first, second, dh3)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    @Test
    fun `full handshake and bidirectional ratchet`() {
        val alice = party("alice", 1)
        val bob = party("bob", 2)
        val aliceBundle = makeBundle(alice)
        val bobBundle = makeBundle(bob)

        // Alice is the initiator (peerId sorts first).
        val skA = x3dh(alice, bobBundle)
        val (aPriv, aPub) = DoubleRatchet.generateDhKeyPair()
        alice.state = DoubleRatchet.initiatorState(skA, aPriv, aPub, bobBundle.spkPub)
        alice.pendingInit = RatchetEnvelope.encodeHeader(RatchetHeader(aPub, 0, 0))

        val skB = x3dh(bob, aliceBundle)
        bob.state = DoubleRatchet.responderState(skB, bob.spkPriv, bob.spkPub)
        // Alice's ratchet_init arrives:
        bob.state = DoubleRatchet.processRatchetInit(bob.state!!, "alice|bob", "alice", alice.pendingInit!!)

        val sessionId = RatchetAad.sessionId("alice", "bob")

        // Bob -> Alice (first send ratchets Bob's chain; Alice ratchets on receipt).
        val (bobNext, env1) = DoubleRatchet.encrypt(bob.state!!, sessionId, "bob", "offer", "b1".toByteArray())
        bob.state = bobNext
        val (aliceNext, p1) = DoubleRatchet.decrypt(alice.state!!, sessionId, "bob", "offer", env1)
        alice.state = aliceNext
        assertArrayEquals("b1".toByteArray(), p1)

        // Alice -> Bob on her new chain.
        val (alice2, env2) = DoubleRatchet.encrypt(alice.state!!, sessionId, "alice", "offer", "a1".toByteArray())
        alice.state = alice2
        val (bob2, p2) = DoubleRatchet.decrypt(bob.state!!, sessionId, "alice", "offer", env2)
        bob.state = bob2
        assertArrayEquals("a1".toByteArray(), p2)

        // Out-of-order + replay.
        val (bob3, e3) = DoubleRatchet.encrypt(bob.state!!, sessionId, "bob", "offer", "b2".toByteArray())
        val (bob4, e4) = DoubleRatchet.encrypt(bob3, sessionId, "bob", "offer", "b3".toByteArray())
        bob.state = bob4
        val (alice3, p4) = DoubleRatchet.decrypt(alice.state!!, sessionId, "bob", "offer", e4)
        alice.state = alice3
        assertArrayEquals("b3".toByteArray(), p4)
        val (alice4, p3) = DoubleRatchet.decrypt(alice.state!!, sessionId, "bob", "offer", e3)
        alice.state = alice4
        assertArrayEquals("b2".toByteArray(), p3)
        assertThrows(Exception::class.java) {
            DoubleRatchet.decrypt(alice.state!!, sessionId, "bob", "offer", e3)
        }
    }

    @Test
    fun `legacy bundle is refused`() {
        val legacy = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1)
        assertThrows(PeerMustUpgradeException::class.java) { PreKeyBundleCodec.decode(legacy) }
    }
}
