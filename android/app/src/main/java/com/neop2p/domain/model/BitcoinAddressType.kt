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
 */
enum class BitcoinAddressType(
    val inputVsize: Long,
    val outputVsize: Long,
    val spendVsize: Long
) {
    LEGACY(148L, 34L, 220L),
    SEGWIT(68L, 31L, 104L)
}
