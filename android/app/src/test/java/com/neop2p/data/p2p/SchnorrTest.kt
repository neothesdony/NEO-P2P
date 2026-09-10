package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchnorrTest {

    private fun hex(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                    Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    // BIP-340 official test vectors (verify)
    @Test
    fun `vector0 valid signature`() {
        val pk = hex("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9")
        val msg = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val sig = hex("E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0")
        assertTrue(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector1 valid signature`() {
        val pk = hex("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val sig = hex("6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A")
        assertTrue(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector2 valid signature`() {
        val pk = hex("DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8")
        val msg = hex("7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C")
        val sig = hex("5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1BAB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7")
        assertTrue(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector3 valid signature with all-ff aux`() {
        val pk = hex("25D1DFF95105F5253C4022F628A996AD3A0D95FBF21D468A1B33F8C160D8F517")
        val msg = hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")
        val sig = hex("7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3")
        assertTrue(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector4 valid signature with r not on curve`() {
        val pk = hex("D69C3509BB99E412E68B0FE8544E72837DFA30746D8BE2AA65975F29D22DC7B9")
        val msg = hex("4DF3C3F68FCC83B27E9D42C90431A72499F17875C81A599B566C9889B9696703")
        val sig = hex("00000000000000000000003B78CE563F89A0ED9414F5AA28AD0D96D6795F9C6376AFB1548AF603B3EB45C9F8207DEE1060CB71C04E80F593060B07D28308D7F4")
        assertTrue(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector5 rejects public key not on curve`() {
        val pk = hex("EEFDEA4CDB677750A420FEE807EACF21EB9898AE79B9768766E4FAA04A2D4A34")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val sig = hex("6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E17776969E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B")
        assertFalse(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector6 rejects odd R`() {
        val pk = hex("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val sig = hex("FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A14602975563CC27944640AC607CD107AE10923D9EF7A73C643E166BE5EBEAFA34B1AC553E2")
        assertFalse(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector7 rejects negated message`() {
        val pk = hex("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val sig = hex("1FA62E331EDBC21C394792D2AB1100A7B432B013DF3F6FF4F99FCB33E0E1515F28890B3EDB6E7189B630448B515CE4F8622A954CFE545735AAEA5134FCCDB2BD")
        assertFalse(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector8 rejects negated s`() {
        val pk = hex("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val sig = hex("6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769961764B3AA9B2FFCB6EF947B6887A226E8D7C93E00C5ED0C1834FF0D0C2E6DA6")
        assertFalse(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector11 rejects r not on curve`() {
        val pk = hex("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val sig = hex("4A298DACAE57395A15D0795DDBFD1DCB564DA82B0F269BC70A74F8220429BA1D69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B")
        assertFalse(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector12 rejects r equal to field size`() {
        val pk = hex("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val sig = hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B")
        assertFalse(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector13 rejects s equal to curve order`() {
        val pk = hex("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val sig = hex("6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141")
        assertFalse(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `vector14 rejects pk exceeding field size`() {
        val pk = hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC30")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val sig = hex("6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E17776969E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B")
        assertFalse(Schnorr.verify(pk, msg, sig))
    }

    // Sign + verify round-trips (vectors 15-18: variable message sizes)
    @Test
    fun `sign and verify round trip empty message`() {
        val sk = hex("0340034003400340034003400340034003400340034003400340034003400340")
        val msg = ByteArray(0)
        val sig = Schnorr.sign(sk, msg, hex("0000000000000000000000000000000000000000000000000000000000000000"))
        val pk = Schnorr.pubKey(sk)
        assertTrue(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `sign and verify round trip 100 byte message`() {
        val sk = hex("0340034003400340034003400340034003400340034003400340034003400340")
        val msg = hex("99".repeat(100))
        val sig = Schnorr.sign(sk, msg, hex("0000000000000000000000000000000000000000000000000000000000000000"))
        val pk = Schnorr.pubKey(sk)
        assertTrue(Schnorr.verify(pk, msg, sig))
    }

    @Test
    fun `sign produces deterministic signature matching vector15`() {
        val sk = hex("0340034003400340034003400340034003400340034003400340034003400340")
        val msg = ByteArray(0)
        val aux = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val sig = Schnorr.sign(sk, msg, aux)
        assertEquals(
            "71535DB165ECD9FBBC046E5FFAEA61186BB6AD436732FCCC25291A55895464CF6069CE26BF03466228F19A3A62DB8A649F2D560FAC652827D1AF0574E427AB63".lowercase(),
            toHex(sig)
        )
    }

    @Test
    fun `tampered signature fails verification`() {
        val sk = hex("0340034003400340034003400340034003400340034003400340034003400340")
        val msg = "hello".toByteArray()
        val sig = Schnorr.sign(sk, msg, ByteArray(32))
        val tampered = sig.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(Schnorr.verify(Schnorr.pubKey(sk), msg, tampered))
    }
}
