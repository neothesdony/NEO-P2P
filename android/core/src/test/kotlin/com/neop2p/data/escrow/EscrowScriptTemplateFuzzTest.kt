package com.neop2p.data.escrow

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class EscrowScriptTemplateFuzzTest {

    private val rng = Random(0x5C21F7L)

    @Test
    fun `detect never throws and only returns known templates`() {
        repeat(5_000) {
            val program = ByteArray(rng.nextInt(128)).also(rng::nextBytes)
            // detect is structural and fail-closed: it must never throw on
            // malformed input, and any non-null result must be one of the two
            // known templates (F3, 2026-09-23). Random bytes that happen to
            // match the bare 2-of-3 outline legitimately detect as V0.
            val detected = EscrowScriptTemplate.detect(program)
            if (detected != null) {
                assertTrue(
                    "unexpected template $detected for ${program.size} bytes",
                    detected == EscrowScriptTemplate.MULTISIG_2OF3_V0 ||
                        detected == EscrowScriptTemplate.MULTISIG_2OF3_CLTV_V1
                )
            }
        }
    }

    @Test
    fun `detect returns null for an empty program`() {
        assertNull(EscrowScriptTemplate.detect(ByteArray(0)))
    }

    @Test
    fun `detect returns null for a non-template program`() {
        assertNull(EscrowScriptTemplate.detect(byteArrayOf(0x52, 0x00)))
    }
}
