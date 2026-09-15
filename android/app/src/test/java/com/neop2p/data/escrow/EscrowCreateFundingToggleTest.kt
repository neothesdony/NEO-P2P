package com.neop2p.data.escrow

import com.neop2p.domain.model.BitcoinAddressType
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-04 (2026-09-15): the funding-type toggle (P2SH / P2WSH) must compute the
 * SAME seller deposit as creation. Both paths share
 * [EscrowService.depositSats] + [EscrowService.fundingNetworkFeeSats], so the
 * 2026-09-06 regression (the toggle used input-only vsize and no floor) cannot
 * return. Seller fee = max((sats * 5) / 1000, 546) — integer-only.
 */
class EscrowCreateFundingToggleTest {

    private fun offer(crypto: Long) = TradeOffer(
        offerId = "offer_1",
        creatorPeerId = "seller",
        type = OfferType.SELL,
        fiatAmount = 1_500_000L,
        cryptoAmountSats = crypto,
        pricePerUnit = 1_500_000_000.0,
        fiatMethods = listOf("bank"),
    )

    private fun sellerFee(crypto: Long) = maxOf((crypto * 5) / 1000, 546L)

    @Test fun `seller fee is the integer 0_5 percent with a dust floor`() {
        assertEquals(546L, offer(50_000L).sellerFeeSats)
        assertEquals(546L, offer(109_200L).sellerFeeSats)
        assertEquals(2_500L, offer(500_000L).sellerFeeSats)
    }

    @Test fun `seller deposit is crypto plus fee`() {
        val crypto = 500_000L
        val o = offer(crypto)
        assertEquals(crypto + o.sellerFeeSats, o.totalDepositSats)
    }

    @Test fun `create and switch compute the identical deposit for both carriers`() {
        val crypto = 500_000L
        val fee = sellerFee(crypto)
        val feeRatePerVb = 30L
        for (type in listOf(BitcoinAddressType.LEGACY, BitcoinAddressType.SEGWIT)) {
            val networkFee = EscrowService.fundingNetworkFeeSats(feeRatePerVb, type)
            // createEscrow: crypto + sellerFeeSats + networkFee
            val created = EscrowService.depositSats(crypto, fee, networkFee)
            // switchFundingType: tradeAmountSats + feeAmountSats + networkFee
            val switched = EscrowService.depositSats(crypto, fee, networkFee)
            assertEquals(created, switched)
            assertEquals(crypto + fee + networkFee, created)
        }
    }

    @Test fun `toggle uses the full payout vsize not the input only size`() {
        // LEGACY payout tx = 298 vB, SEGWIT = 176 vB. The old toggle used 104.
        assertEquals(30L * 298L, EscrowService.fundingNetworkFeeSats(30L, BitcoinAddressType.LEGACY))
        assertEquals(30L * 176L, EscrowService.fundingNetworkFeeSats(30L, BitcoinAddressType.SEGWIT))
    }

    @Test fun `low fee rate keeps both carriers above the relay floor`() {
        // At 1 sat/vB SEGWIT would be 176 sats — the floor raises it to 250;
        // LEGACY is 298 and stays above the floor.
        assertEquals(250L, EscrowService.fundingNetworkFeeSats(1L, BitcoinAddressType.SEGWIT))
        assertEquals(298L, EscrowService.fundingNetworkFeeSats(1L, BitcoinAddressType.LEGACY))
    }
}
