package com.neop2p.domain.model

/**
 * How a Bitcoin address/script is encoded on-chain.
 *
 * Both types are derived from the SAME BIP-44 key (`m/44'/0'/0'/0/0`) — only
 * the script carrier differs — so a user can hold legacy + SegWit addresses
 * side by side with zero key migration.
 *
 * VSIZE constants (approximate, sat/vB math):
 *  - LEGACY:  P2PKH input ≈ 148 vB, P2PKH output ≈ 34 vB.
 *  - SEGWIT:  P2WPKH input ≈ 68 vB (sigs live in the witness), P2WPKH output
 *             ≈ 31 vB (witness program is shorter than a full pubkey hash).
 *  - spendVsize: a 2-of-3 multisig spend — P2SH ≈ 220 vB (2 DER sigs + redeem
 *             script in the scriptSig) vs P2WSH ≈ 104 vB (sig/script data in
 *             the witness, 4× cheaper per byte).
 *  - payoutTxVsize: full payout tx vsize = multisig input + buyer output +
 *             fee output + fixed overhead (version + locktime + marker/flag).
 */
enum class BitcoinAddressType(
    val inputVsize: Long,
    val outputVsize: Long,
    val spendVsize: Long,
    val payoutTxVsize: Long
) {
    /** P2SH payout: 220 (multisig input) + 34 (P2PKH buyer) + 34 (P2PKH fee) + 10 (overhead). */
    LEGACY(148L, 34L, 220L, 298L),
    /** P2WSH payout: 104 (multisig input) + 31 (P2WPKH buyer) + 31 (P2WPKH fee) + 10 (overhead). */
    SEGWIT(68L, 31L, 104L, 176L);

    companion object {
        /** Fixed tx overhead: version (4) + locktime (4) + marker (1) + flag (1) = 10 vB. */
        const val FIXED_OVERHEAD_VSIZE = 10L
    }
}
