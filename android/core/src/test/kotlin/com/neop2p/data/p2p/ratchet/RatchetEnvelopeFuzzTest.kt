package com.neop2p.data.p2p.ratchet

import org.junit.Test
import java.util.Random

class RatchetEnvelopeFuzzTest {

    private val rng = Random(0xE2E2E2L)

    @Test
    fun `decode rejects malformed input with IllegalArgumentException only`() {
        repeat(5_000) {
            val bytes = ByteArray(rng.nextInt(512)).also(rng::nextBytes)
            try {
                RatchetEnvelope.decode(bytes)
            } catch (e: IllegalArgumentException) {
                // Documented rejection path.
            } catch (e: Throwable) {
                throw AssertionError("unexpected ${e::class.java.name} for ${bytes.size} bytes", e)
            }
        }
    }

    @Test
    fun `decodeHeader rejects malformed input with IllegalArgumentException only`() {
        repeat(5_000) {
            val bytes = ByteArray(rng.nextInt(128)).also(rng::nextBytes)
            try {
                RatchetEnvelope.decodeHeader(bytes)
            } catch (e: IllegalArgumentException) {
                // Documented rejection path.
            } catch (e: Throwable) {
                throw AssertionError("unexpected ${e::class.java.name}", e)
            }
        }
    }
}
