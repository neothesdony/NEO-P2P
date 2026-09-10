package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResendQueueTest {

    private val resendable = setOf(
        "offer_status", "offer_delete", "escrow_status", "dispute",
        "evidence", "resolution", "offer_request", "offer", "attestation"
    )
    private val maxBytes = 16 * 1024

    @Test
    fun `signaling types are resendable`() {
        for (type in resendable) {
            assertTrue(resendQueueAllowed(type, 100, resendable, maxBytes))
        }
    }

    @Test
    fun `chat and prekey are never queued for resend`() {
        assertFalse(resendQueueAllowed("chat", 100, resendable, maxBytes))
        assertFalse(resendQueueAllowed("prekey_bundle", 100, resendable, maxBytes))
        assertFalse(resendQueueAllowed("unknown", 100, resendable, maxBytes))
    }

    @Test
    fun `oversized payloads are rejected`() {
        assertFalse(resendQueueAllowed("offer_status", maxBytes + 1, resendable, maxBytes))
        assertTrue(resendQueueAllowed("offer_status", maxBytes, resendable, maxBytes))
    }

    @Test
    fun `key is stable per peer-type-payload`() {
        val a = resendQueueKey("peer1", "offer_status", "{\"a\":1}".toByteArray())
        val b = resendQueueKey("peer1", "offer_status", "{\"a\":1}".toByteArray())
        assertTrue(a == b)
        // Different peer or different payload → different key (dedup only
        // collapses identical retries).
        assertTrue(a != resendQueueKey("peer2", "offer_status", "{\"a\":1}".toByteArray()))
        assertTrue(a != resendQueueKey("peer1", "offer_status", "{\"a\":2}".toByteArray()))
    }

    @Test
    fun `queueResend helper exists on RnsSession`() {
        // Compile-time contract: sendSignaling's failure path must route
        // through the same policy function the failed-delivery callback uses.
        // (Behavior is covered by the policy tests + live device verification;
        // this pins the shared entry point.)
        val callable = Class.forName("com.neop2p.data.p2p.RnsSession")
            .declaredMethods.any { it.name == "queueResend" }
        assertTrue("RnsSession.queueResend must exist", callable)
    }
}
