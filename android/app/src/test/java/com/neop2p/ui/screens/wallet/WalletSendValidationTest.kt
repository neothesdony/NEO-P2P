package com.neop2p.ui.screens.wallet

import com.neop2p.data.wallet.BtcAddressError
import com.neop2p.data.wallet.btcAddressError
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for send-amount validation (audit P3-7, 2026-09-12) and
 * destination validation (P3.1).
 *
 * The screen used to validate against confirmed + unconfirmed, while the
 * service can only spend confirmed UTXOs — a send sized on unconfirmed funds
 * passed every UI gate and then failed at broadcast with a raw error.
 */
class WalletSendValidationTest {

    private val mainnet = MainNetParams.get()
    private val testnet = TestNet3Params.get()
    private val key = ECKey.fromPrivate(ByteArray(32) { 1 })

    @Test
    fun `blank input is not an error yet`() {
        assertNull(sendAmountError(amountSats = null, spendableSats = 100_000L))
    }

    @Test
    fun `zero and negative are invalid amounts`() {
        assertEquals(WalletInputError.INVALID_AMOUNT, sendAmountError(0L, 100_000L))
        assertEquals(WalletInputError.INVALID_AMOUNT, sendAmountError(-5L, 100_000L))
    }

    @Test
    fun `below the dust threshold is a dust error`() {
        assertEquals(WalletInputError.DUST, sendAmountError(500L, 100_000L))
    }

    @Test
    fun `exactly the dust threshold is allowed`() {
        assertNull(sendAmountError(546L, 100_000L))
    }

    @Test
    fun `above spendable balance is insufficient`() {
        assertEquals(WalletInputError.INSUFFICIENT_BALANCE, sendAmountError(100_001L, 100_000L))
    }

    @Test
    fun `equal to spendable balance passes (fee may still reject it later)`() {
        assertNull(sendAmountError(100_000L, 100_000L))
    }

    // ─── P3.1 destination validation ──────────────────────────────

    @Test
    fun `valid p2wpkh and p2pkh are accepted on both networks`() {
        assertNull(btcAddressError(SegwitAddress.fromKey(mainnet, key).toBech32(), mainnet, testnet))
        assertNull(btcAddressError(LegacyAddress.fromKey(mainnet, key).toBase58(), mainnet, testnet))
        assertNull(btcAddressError(SegwitAddress.fromKey(testnet, key).toBech32(), testnet, mainnet))
        assertNull(btcAddressError(LegacyAddress.fromKey(testnet, key).toBase58(), testnet, mainnet))
    }

    @Test
    fun `blank destination is not yet an error`() {
        assertNull(btcAddressError("", testnet, mainnet))
    }

    @Test
    fun `wrong network is distinguished from invalid`() {
        val mainnetAddr = LegacyAddress.fromKey(mainnet, key).toBase58()
        assertEquals(BtcAddressError.WRONG_NETWORK, btcAddressError(mainnetAddr, testnet, mainnet))
    }

    @Test
    fun `malformed address is invalid`() {
        assertEquals(BtcAddressError.INVALID, btcAddressError("not-an-address", testnet, mainnet))
        assertEquals(BtcAddressError.INVALID, btcAddressError("tb1q", testnet, mainnet))
    }

    @Test
    fun `taproot witness v1 is explicitly unsupported`() {
        val p2tr = SegwitAddress.fromProgram(testnet, 1, ByteArray(32) { 2 }).toBech32()
        assertEquals(BtcAddressError.TAPROOT_UNSUPPORTED, btcAddressError(p2tr, testnet, mainnet))
    }

    @Test
    fun `future witness versions are rejected`() {
        val v2 = SegwitAddress.fromProgram(testnet, 2, ByteArray(32) { 3 }).toBech32()
        assertEquals(BtcAddressError.INVALID, btcAddressError(v2, testnet, mainnet))
    }

    @Test
    fun `input error mapping is consistent`() {
        assertNull(btcAddressInputError(null))
        assertEquals(WalletInputError.WRONG_NETWORK, btcAddressInputError(BtcAddressError.WRONG_NETWORK))
        assertEquals(WalletInputError.UNSUPPORTED_ADDRESS, btcAddressInputError(BtcAddressError.TAPROOT_UNSUPPORTED))
        assertEquals(WalletInputError.INVALID_ADDRESS, btcAddressInputError(BtcAddressError.INVALID))
    }
}
