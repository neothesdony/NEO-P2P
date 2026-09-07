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
