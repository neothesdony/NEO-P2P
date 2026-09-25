package com.neop2p.data.network

/** Single definition of Tor state, shared by the Android manager and the gate. */
sealed interface TorState {
    data object Disabled : TorState
    data object Starting : TorState
    data class Bootstrapping(val percent: Int) : TorState
    data class Connected(val httpPort: Int) : TorState
    data class Failed(val reason: String) : TorState
}

enum class TorVerdict { ALLOW_DIRECT, ALLOW_TOR, BLOCK }

/**
 * Pure routing decision. Fail-closed: when Tor is enabled and not connected,
 * HTTP is blocked unless the caller holds an explicit one-shot override.
 */
object TorGate {
    fun verdict(enabled: Boolean, state: TorState, override: Boolean): TorVerdict = when {
        !enabled -> TorVerdict.ALLOW_DIRECT
        state is TorState.Connected -> TorVerdict.ALLOW_TOR
        override -> TorVerdict.ALLOW_DIRECT
        else -> TorVerdict.BLOCK
    }

    fun blockReason(state: TorState): String? = when (state) {
        TorState.Disabled -> null
        TorState.Starting -> "tor_starting"
        is TorState.Bootstrapping -> "tor_bootstrapping"
        is TorState.Connected -> null
        is TorState.Failed -> "tor_failed"
    }
}
