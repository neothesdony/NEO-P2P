package com.neop2p.data.escrow

/**
 * Escrow redeem-script templates. V0 is the legacy 2-of-3. V1 adds a
 * CHECKLOCKTIMEVERIFY escape branch so the seller can recover a deposit
 * without the arbitrator after a fixed maturity (C9). Unknown scripts are
 * rejected everywhere (fail closed) — this is a compatibility hard fork.
 */
enum class EscrowScriptTemplate(val id: String) {
    MULTISIG_2OF3_V0("MULTISIG_2OF3_V0"),
    MULTISIG_2OF3_CLTV_V1("MULTISIG_2OF3_CLTV_V1");

    companion object {
        const val CLTV_OPCODE_HEX = "b1" // OP_CHECKLOCKTIMEVERIFY

        fun fromId(id: String?): EscrowScriptTemplate? = entries.firstOrNull { it.id == id }

        /** Structural detection; null for anything unrecognised. */
        fun detect(program: ByteArray): EscrowScriptTemplate? {
            if (program.isEmpty()) return null
            val hex = program.joinToString("") { "%02x".format(it) }
            if (program.first().toInt() and 0xff == 0x63 && hex.contains(CLTV_OPCODE_HEX)) {
                return MULTISIG_2OF3_CLTV_V1
            }
            // V0 is a bare 2-of-3 multisig: starts OP_2, ends OP_3 OP_CHECKMULTISIG.
            if (program.first().toInt() and 0xff == 0x52 &&
                program.last().toInt() and 0xff == 0xae
            ) {
                return MULTISIG_2OF3_V0
            }
            return null
        }
    }
}
