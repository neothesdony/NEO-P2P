package com.neop2p.data.p2p

import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.MessageState
import java.security.MessageDigest

/** App-level outbound chat delivery state (mirrors Columba's closed enum). */
enum class ChatDeliveryStatus(val wire: String) {
    PENDING("pending"),
    SENT("sent"),
    PROPAGATED("propagated"),
    DELIVERED("delivered"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String): ChatDeliveryStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Maps the LXMF fork's message state to the app status. */
object ChatDeliveryStatusMapper {
    fun map(state: MessageState, method: DeliveryMethod?): ChatDeliveryStatus = when (state) {
        MessageState.DELIVERED -> ChatDeliveryStatus.DELIVERED
        MessageState.FAILED, MessageState.REJECTED, MessageState.CANCELLED -> ChatDeliveryStatus.FAILED
        MessageState.SENT ->
            if (method == DeliveryMethod.PROPAGATED) ChatDeliveryStatus.PROPAGATED else ChatDeliveryStatus.SENT
        else -> ChatDeliveryStatus.PENDING
    }
}

/**
 * Monotonic transition table. `DELIVERED` is terminal; `PROPAGATED` is never
 * downgraded to `SENT`; `FAILED` may still recover to `DELIVERED`. A late
 * `SENT`/`PENDING` event can never regress a terminal row.
 */
object ChatDeliveryStatusReducer {
    fun apply(current: ChatDeliveryStatus, incoming: ChatDeliveryStatus): ChatDeliveryStatus = when (incoming) {
        ChatDeliveryStatus.DELIVERED -> ChatDeliveryStatus.DELIVERED
        ChatDeliveryStatus.PROPAGATED ->
            if (current == ChatDeliveryStatus.DELIVERED) current else ChatDeliveryStatus.PROPAGATED
        ChatDeliveryStatus.FAILED ->
            if (current == ChatDeliveryStatus.DELIVERED) current else ChatDeliveryStatus.FAILED
        ChatDeliveryStatus.SENT ->
            if (current == ChatDeliveryStatus.PENDING || current == ChatDeliveryStatus.SENT) ChatDeliveryStatus.SENT else current
        ChatDeliveryStatus.PENDING -> current
    }
}

/** Correlation token for an outbound ciphertext; also the persisted message id. */
object ChatDeliveryToken {
    fun of(ciphertext: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(ciphertext).take(16)
            .joinToString("") { "%02x".format(it) }
}
