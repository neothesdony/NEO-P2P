package com.neop2p.data.wallet

import com.neop2p.domain.model.BitcoinAddressType

/**
 * One derived address known to the wallet, tagged with the derivation that
 * produced it so the UTXO loop can map it back to a per-input key + script type
 * (P0.5/P0.6).
 */
data class ScannedAddress(
    val type: BitcoinAddressType,
    val index: Int,
    val internal: Boolean,
    val address: String
)

/**
 * The SINGLE list of addresses the wallet scans (P0.4). Balance (`loadState`),
 * UTXO collection (`estimateSendFee` / `send`), and the receive watcher
 * ([com.neop2p.service.WalletWatcher]) all consume this — a change address no
 * UTXO loop can see is a fund-visibility bug, not a privacy bug.
 *
 * Composition:
 *  - external `scanSet(nextExternal)` for BOTH legacy + SegWit,
 *  - internal `scanSet(nextChange)` for SEGWIT only (change is p2wpkh-pinned),
 *  - every `reserved` index (external, both types),
 *  - index 0 external for both types, unconditionally — existing funds and the
 *    escrow role addresses depend on it.
 *
 * Deduplicated by address, so the same index can never be scanned twice.
 */
fun scanAddresses(
    pointers: HdPointers,
    derive: (BitcoinAddressType, Int, Boolean) -> String
): List<ScannedAddress> {
    val out = LinkedHashMap<String, ScannedAddress>()

    fun add(type: BitcoinAddressType, index: Int, internal: Boolean) {
        val address = derive(type, index, internal)
        if (address.isBlank()) return
        out.putIfAbsent(address, ScannedAddress(type, index, internal, address))
    }

    for (index in scanSet(pointers.nextExternal)) {
        for (type in BitcoinAddressType.entries) add(type, index, internal = false)
    }
    for (index in scanSet(pointers.nextChange)) {
        add(BitcoinAddressType.SEGWIT, index, internal = true)
    }
    for (index in pointers.reserved) {
        for (type in BitcoinAddressType.entries) add(type, index, internal = false)
    }
    // Unconditional: never lose index 0.
    for (type in BitcoinAddressType.entries) add(type, 0, internal = false)

    return out.values.toList()
}
