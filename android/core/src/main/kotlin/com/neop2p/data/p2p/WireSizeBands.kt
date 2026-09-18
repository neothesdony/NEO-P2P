package com.neop2p.data.p2p

/** Wire-size bands for LXMF-kt / rns-core. See docs/WIRE_SIZE_BANDS.md. */
object WireSizeBands {
    const val MAX_INBOUND_KB = 128          // LXMRouter.incomingMessageSizeLimitKb
    const val OPPORTUNISTIC_MAX_BYTES = 295 // ENCRYPTED_PACKET_MAX_CONTENT
    const val LINK_PACKET_MAX_BYTES = 319   // LINK_PACKET_MAX_CONTENT; >319B → Resource
}
