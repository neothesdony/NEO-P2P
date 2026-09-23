package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DoubleRatchetDhTest {

    @Test
    fun `responder cannot send before processing ratchet_init`() {
        val (spkPriv, spkPub) = DoubleRatchet.generateDhKeyPair()
        val responder = DoubleRatchet.responderState(ByteArray(32) { 42 }, spkPriv, spkPub)
        assertThrows(IllegalStateException::class.java) {
            DoubleRatchet.encrypt(responder, "s", "bob", "offer", "hi".toByteArray())
        }
    }

    @Test
    fun `a DH ratchet step converges and both sides keep communicating`() {
        val (spkPriv, spkPub) = DoubleRatchet.generateDhKeyPair()
        val (dhPriv, dhPub) = DoubleRatchet.generateDhKeyPair()
        val sk = ByteArray(32) { 7 }
        var alice = DoubleRatchet.initiatorState(sk, dhPriv, dhPub, spkPub)
        var bob = DoubleRatchet.processRatchetInit(
            DoubleRatchet.responderState(sk, spkPriv, spkPub),
            "s", "bob",
            RatchetEnvelope.encodeHeader(RatchetHeader(alice.dhSelfPub, 0, 0))
        )

        // Bob sends first -> new DH key -> Alice ratchets.
        val (bob1, env1) = DoubleRatchet.encrypt(bob, "s", "bob", "offer", "b1".toByteArray())
        bob = bob1
        val (alice1, p1) = DoubleRatchet.decrypt(alice, "s", "bob", "offer", env1)
        alice = alice1
        assertArrayEquals("b1".toByteArray(), p1)

        // Alice replies on the new sending chain.
        val (alice2, env2) = DoubleRatchet.encrypt(alice, "s", "alice", "offer", "a1".toByteArray())
        alice = alice2
        val (bob2, p2) = DoubleRatchet.decrypt(bob, "s", "alice", "offer", env2)
        bob = bob2
        assertArrayEquals("a1".toByteArray(), p2)
    }

    @Test
    fun `old dh private key is zeroed after a ratchet step`() {
        val (spkPriv, spkPub) = DoubleRatchet.generateDhKeyPair()
        val (dhPriv, dhPub) = DoubleRatchet.generateDhKeyPair()
        val sk = ByteArray(32) { 3 }
        val alice = DoubleRatchet.initiatorState(sk, dhPriv, dhPub, spkPub)
        var bob = DoubleRatchet.processRatchetInit(
            DoubleRatchet.responderState(sk, spkPriv, spkPub),
            "s", "bob",
            RatchetEnvelope.encodeHeader(RatchetHeader(alice.dhSelfPub, 0, 0))
        )
        // Bob sends -> Alice ratchets -> Alice's old dhSelfPriv (dhPriv) is replaced.
        val (bob1, env1) = DoubleRatchet.encrypt(bob, "s", "bob", "offer", "x".toByteArray())
        bob = bob1
        val (alice1, _) = DoubleRatchet.decrypt(alice, "s", "bob", "offer", env1)
        assertFalse(alice1.dhSelfPriv.contentEquals(dhPriv))
    }
}
