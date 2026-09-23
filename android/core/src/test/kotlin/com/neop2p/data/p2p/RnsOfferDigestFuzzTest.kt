package com.neop2p.data.p2p

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class RnsOfferDigestFuzzTest {

    private val rng = Random(0x0FF1CEL)

    @Test
    fun `decode never throws on random bytes`() {
        repeat(5_000) {
            val bytes = ByteArray(rng.nextInt(256)).also(rng::nextBytes)
            val result = RnsOfferDigest.decode(bytes.toString(Charsets.ISO_8859_1))
            if (result != null) {
                // A decoded digest must always expose a stable id accessor.
                RnsOfferDigest.offerIdOf(result)
            }
        }
    }

    @Test
    fun `decode never throws on mutated valid digests`() {
        val valid = RnsOfferDigest.encodeTombstone("offer_123")
        repeat(5_000) {
            val chars = valid.toCharArray()
            repeat(rng.nextInt(4)) {
                val i = rng.nextInt(chars.size)
                chars[i] = (rng.nextInt(0x7F)).toChar()
            }
            RnsOfferDigest.decode(String(chars))
        }
    }

    @Test
    fun `only version 1 objects decode`() {
        assertNull(RnsOfferDigest.decode("""{"v":2,"id":"x"}"""))
        assertNull(RnsOfferDigest.decode("not json"))
        assertNotNull(RnsOfferDigest.decode("""{"v":1,"id":"x","h":"abc"}"""))
    }
}
