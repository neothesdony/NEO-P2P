package com.neop2p.data.portability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BundleCodecTest {
    private fun sample() = IdentityBundle(
        version = 1,
        peerId = "12D3KooExample",
        mnemonic = List(12) { "word$it" },
        nickname = "Tester",
        lnNodeId = "",
        walletExternalPointer = 7,
        walletChangePointer = 2,
        escrows = listOf(
            BundleEscrow(
                escrowId = "escrow_o_1",
                offerId = "o",
                status = "FUNDED",
                depositAmountSats = 100_000,
                tradeAmountSats = 99_000,
                feeAmountSats = 500,
                networkFeeSats = 300,
                feeAddress = "bc1qfeewallet",
                buyerPeerId = "buyer",
                sellerPeerId = "seller",
                fundingAddress = "bc1qescrow",
                fundingTxId = "txid",
                psbtUnsigned = null
            )
        ),
        offers = listOf(
            BundleOffer(
                offerId = "o",
                creatorPeerId = "seller",
                type = "SELL",
                fiatAmount = 1_000_000,
                cryptoAmountSats = 99_000,
                pricePerUnit = 1_010_101_010.0,
                status = "MATCHED"
            )
        )
    )

    @Test
    fun roundTripPreservesAllFields() {
        val decoded = BundleCodec.decode(BundleCodec.encode(sample()))
        assertEquals(sample(), decoded)
    }

    @Test
    fun unknownVersionIsRejected() {
        val json = BundleCodec.encode(sample()).replace("\"version\":1", "\"version\":99")
        assertThrows(IllegalArgumentException::class.java) { BundleCodec.decode(json) }
    }

    @Test
    fun malformedJsonIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { BundleCodec.decode("{not json") }
    }
}
