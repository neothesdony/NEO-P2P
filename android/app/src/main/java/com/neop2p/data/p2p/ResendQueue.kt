package com.neop2p.data.p2p

/**
 * Pure policy for the failed-signaling resend queue (S05/S06). Both the
 * mid-flight failure callback (LXMF failed-delivery) and the send-time
 * failure path in [RnsSession.sendSignaling] share these rules so a
 * signaling message lost at ANY stage is retried identically.
 *
 * Kept free of RnsSession state so the rules are plain-JUnit testable.
 */
internal fun resendQueueKey(peerId: String, type: String, data: ByteArray): String =
    "$peerId|$type|${data.contentHashCode()}"

internal fun resendQueueAllowed(
    type: String,
    size: Int,
    resendableTypes: Set<String>,
    maxPayloadBytes: Int
): Boolean = type in resendableTypes && size <= maxPayloadBytes

/** Max times a failed DIRECT message may be re-handed to the propagation node. */
const val MAX_PROPAGATION_FALLBACK_ATTEMPTS = 2

/**
 * The failed-delivery callback re-sends via the propagation node when one is
 * active. Without a cap a persistently-failing propagation path re-fires the
 * callback forever (the 2026-09-15 1–3 Hz escrow_status loop). After the cap,
 * fall back to the bounded announce-flush resend queue.
 */
fun propagationFallbackAllowed(attempts: Int): Boolean = attempts < MAX_PROPAGATION_FALLBACK_ATTEMPTS
