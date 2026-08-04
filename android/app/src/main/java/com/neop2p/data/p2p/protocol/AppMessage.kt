package com.neop2p.data.p2p.protocol

sealed interface AppMessage {
    val type: String
    val to: String
    // Sender peerId, populated by EnvelopeCodec.decode from the transport
    // envelope's fromPeerId. Empty on outbound messages.
    val from: String

    data class PreKeyRequest(override val to: String, override val from: String = "") : AppMessage {
        override val type = "pre_key_request"
    }

    data class PreKeyBundle(override val to: String, val bundle: ByteArray, override val from: String = "") : AppMessage {
        override val type = "pre_key_bundle"
    }

    data class Chat(override val to: String, val offerId: String, val ciphertext: ByteArray, override val from: String = "") : AppMessage {
        override val type = "chat"
    }

    data class Offer(override val to: String, val offerJson: String, override val from: String = "") : AppMessage {
        override val type = "offer"
    }

    data class EscrowEvent(
        override val to: String,
        val escrowId: String,
        val event: String,
        val payload: ByteArray,
        override val from: String = ""
    ) : AppMessage {
        override val type = "escrow_event"
    }
}
