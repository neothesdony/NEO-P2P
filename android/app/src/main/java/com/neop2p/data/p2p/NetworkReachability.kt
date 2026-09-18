package com.neop2p.data.p2p

/** The transport class of the device's current default network. */
enum class CurrentTransport { WIFI_LIKE, CELLULAR, UNKNOWN, NONE }

/** Per-interface network restriction (Columba `networkRestriction` analogue). */
enum class NetworkRestriction { ANY, WIFI_ONLY, CELLULAR_ONLY }

/**
 * Pure filter for NEO-P2P's IP interfaces (TCP transport nodes + AutoInterface).
 * `UNKNOWN` (VPN-only default) is permissive so unrestricted interfaces stay
 * up without falsely enabling cellular-only ones; `NONE` drops everything.
 */
object NetworkReachability {

    fun passes(restriction: NetworkRestriction, transport: CurrentTransport): Boolean {
        if (transport == CurrentTransport.NONE) return false
        return when (restriction) {
            NetworkRestriction.ANY -> true
            NetworkRestriction.WIFI_ONLY ->
                transport == CurrentTransport.WIFI_LIKE || transport == CurrentTransport.UNKNOWN
            NetworkRestriction.CELLULAR_ONLY -> transport == CurrentTransport.CELLULAR
        }
    }

    /** AutoInterface is UDP multicast: dead on cellular, so default Wi-Fi-only. */
    fun autoInterfaceEnabled(transport: CurrentTransport, wifiOnly: Boolean): Boolean =
        passes(if (wifiOnly) NetworkRestriction.WIFI_ONLY else NetworkRestriction.ANY, transport)
}
