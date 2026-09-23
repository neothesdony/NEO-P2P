package com.neop2p.data.escrow

import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptOpCodes

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
        val CLTV_OPCODE_HEX: String = "%02x".format(ScriptOpCodes.OP_CHECKLOCKTIMEVERIFY)

        fun fromId(id: String?): EscrowScriptTemplate? = entries.firstOrNull { it.id == id }

        /** Structural detection; null for anything unrecognised. */
        fun detect(program: ByteArray): EscrowScriptTemplate? {
            if (program.isEmpty()) return null
            if (program.first().toInt() and 0xff == ScriptOpCodes.OP_IF) {
                return if (isCanonicalCltvScript(program)) MULTISIG_2OF3_CLTV_V1 else null
            }
            // V0 is a bare 2-of-3 multisig: starts OP_2, ends OP_3 OP_CHECKMULTISIG.
            if (program.first().toInt() and 0xff == ScriptOpCodes.OP_2 &&
                program.last().toInt() and 0xff == ScriptOpCodes.OP_CHECKMULTISIG
            ) {
                return MULTISIG_2OF3_V0
            }
            return null
        }

        /**
         * Exact opcode outline [EscrowScripts.build] produces for V1. A scan of
         * parsed chunks (not a byte substring) so an unrecognised OP_IF script
         * that merely contains a 0xb1 byte can never be mislabelled.
         */
        private fun isCanonicalCltvScript(program: ByteArray): Boolean {
            val chunks = try {
                Script(program).chunks
            } catch (_: Exception) {
                return false
            }
            if (chunks.size != 14) return false
            fun op(index: Int, opcode: Int) = chunks[index].equalsOpCode(opcode)
            // A numeric operand: push data or a small-int OP_N (OP_1..OP_16).
            // Never call decodeOpN() on an arbitrary chunk — it throws on a
            // non-OP_N opcode, which would break the fail-closed contract
            // (F3 fuzz, 2026-09-23).
            fun number(index: Int) = chunks[index].isPushData() ||
                chunks[index].opcode in ScriptOpCodes.OP_1..ScriptOpCodes.OP_16
            return op(0, ScriptOpCodes.OP_IF) &&
                number(1) &&
                op(2, ScriptOpCodes.OP_CHECKLOCKTIMEVERIFY) &&
                op(3, ScriptOpCodes.OP_DROP) &&
                chunks[4].isPushData() &&
                op(5, ScriptOpCodes.OP_CHECKSIG) &&
                op(6, ScriptOpCodes.OP_ELSE) &&
                op(7, ScriptOpCodes.OP_2) &&
                chunks[8].isPushData() &&
                chunks[9].isPushData() &&
                chunks[10].isPushData() &&
                op(11, ScriptOpCodes.OP_3) &&
                op(12, ScriptOpCodes.OP_CHECKMULTISIG) &&
                op(13, ScriptOpCodes.OP_ENDIF)
        }
    }
}
