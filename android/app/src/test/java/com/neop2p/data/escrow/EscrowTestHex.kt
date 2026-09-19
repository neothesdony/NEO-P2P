package com.neop2p.data.escrow

// Test-local hex helper. EscrowScriptGate's `internal toHex` moved to :core
// (2026-09-18) along with the gate classes, so :app tests that stay behind
// carry their own copy, matching the existing per-file hex helpers.
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
