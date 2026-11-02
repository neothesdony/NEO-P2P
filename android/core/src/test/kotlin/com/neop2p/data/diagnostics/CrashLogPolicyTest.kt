package com.neop2p.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogPolicyTest {

    @Test
    fun `redacts peer ids and bitcoin addresses`() {
        val s = "peer 12D3KooWabc bc1qxyz 0xdeadbeef"
        val out = CrashLogPolicy.redact(s)
        assertFalse(out.contains("12D3KooWabc"))
        assertFalse(out.contains("bc1qxyz"))
        assertEquals("peer <redacted> <redacted> 0xdeadbeef", out)
    }

    @Test
    fun `redacts p2pkh p2sh and bech32 addresses`() {
        val p2pkh = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
        val p2sh = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"
        val bech32 = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val testnet = "tb1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val out = CrashLogPolicy.redact("$p2pkh $p2sh $bech32 $testnet")
        for (a in listOf(p2pkh, p2sh, bech32, testnet)) assertFalse(out.contains(a))
    }

    @Test
    fun `rotation keeps at most five files`() {
        assertTrue(CrashLogPolicy.shouldRotate(5))
        assertFalse(CrashLogPolicy.shouldRotate(4))
    }
}
