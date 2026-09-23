package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleRatchetSymmetricTest {

    /** Build a responder state that can send: sk + spk, then process a peer init. */
    private fun establishedPair(): Pair<RatchetState, RatchetState> {
        val (spkPriv, spkPub) = DoubleRatchet.generateDhKeyPair()
        val (dhPriv, dhPub) = DoubleRatchet.generateDhKeyPair()
        val sk = ByteArray(32) { 42 }
        val initiator = DoubleRatchet.initiatorState(sk, dhPriv, dhPub, spkPub)
        val responder0 = DoubleRatchet.responderState(sk, spkPriv, spkPub)
        val initHeader = RatchetEnvelope.encodeHeader(
            RatchetHeader(initiator.dhSelfPub, 0, 0)
        )
        val responder = DoubleRatchet.processRatchetInit(responder0, "s", "bob", initHeader)
        return initiator to responder
    }

    @Test
    fun `in-order messages round-trip both directions`() {
        var (alice, bob) = establishedPair()
        repeat(5) { i ->
            val (next, env) = DoubleRatchet.encrypt(alice, "s", "alice", "offer", "m$i".toByteArray())
            alice = next
            val (bobNext, plain) = DoubleRatchet.decrypt(bob, "s", "alice", "offer", env)
            bob = bobNext
            assertArrayEquals("m$i".toByteArray(), plain)
        }
        val (bobNext, env) = DoubleRatchet.encrypt(bob, "s", "bob", "offer", "reply".toByteArray())
        var bob2 = bobNext
        val (aliceNext, plain) = DoubleRatchet.decrypt(alice, "s", "bob", "offer", env)
        assertArrayEquals("reply".toByteArray(), plain)
    }

    @Test
    fun `out-of-order messages are decrypted from the skipped store`() {
        var (alice, bob) = establishedPair()
        val envelopes = mutableListOf<ByteArray>()
        repeat(4) { i ->
            val (next, env) = DoubleRatchet.encrypt(alice, "s", "alice", "offer", "m$i".toByteArray())
            alice = next
            envelopes.add(env)
        }
        // deliver 3 then 0 then 2 then 1
        for (i in listOf(3, 0, 2, 1)) {
            val (bobNext, plain) = DoubleRatchet.decrypt(bob, "s", "alice", "offer", envelopes[i])
            bob = bobNext
            assertArrayEquals("m$i".toByteArray(), plain)
        }
    }

    @Test
    fun `a replayed message is rejected`() {
        var (alice, bob) = establishedPair()
        val (next, env) = DoubleRatchet.encrypt(alice, "s", "alice", "offer", "once".toByteArray())
        alice = next
        val (bobNext, _) = DoubleRatchet.decrypt(bob, "s", "alice", "offer", env)
        bob = bobNext
        assertThrows(Exception::class.java) {
            DoubleRatchet.decrypt(bob, "s", "alice", "offer", env)
        }
    }

    @Test
    fun `AAD mismatch (wrong offer) is rejected`() {
        var (alice, bob) = establishedPair()
        val (next, env) = DoubleRatchet.encrypt(alice, "s", "alice", "offer-1", "hi".toByteArray())
        alice = next
        assertThrows(Exception::class.java) {
            DoubleRatchet.decrypt(bob, "s", "alice", "offer-2", env)
        }
    }

    @Test
    fun `message keys are not reused across a chain`() {
        var (alice, bob) = establishedPair()
        val seen = mutableSetOf<String>()
        repeat(6) {
            val (next, env) = DoubleRatchet.encrypt(alice, "s", "alice", "offer", "same".toByteArray())
            alice = next
            val (bobNext, _) = DoubleRatchet.decrypt(bob, "s", "alice", "offer", env)
            bob = bobNext
            assertTrue("ciphertexts must differ", seen.add(env.joinToString("") { "%02x".format(it) }))
        }
    }
}
