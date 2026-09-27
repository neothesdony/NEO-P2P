package com.neop2p.data.tor

import com.neop2p.data.network.TorState

/** Maps Tor state to a string resource key (null = no status line shown). */
object TorStatusLabel {
    fun of(state: TorState): String? = when (state) {
        TorState.Disabled -> null
        TorState.Starting -> "tor_status_starting"
        is TorState.Bootstrapping -> "tor_status_bootstrapping"
        is TorState.Connected -> "tor_status_connected"
        is TorState.Failed -> "tor_status_failed"
    }
}
