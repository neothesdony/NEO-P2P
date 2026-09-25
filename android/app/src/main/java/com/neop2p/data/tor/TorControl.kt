package com.neop2p.data.tor

import kotlinx.coroutines.flow.Flow

sealed interface TorControlEvent {
    data class Bootstrap(val percent: Int) : TorControlEvent
    data class Ready(val httpPort: Int) : TorControlEvent
    data class Failure(val reason: String) : TorControlEvent
}

/** Seam over the embedded daemon so the state machine is testable off-device. */
interface TorControl {
    val events: Flow<TorControlEvent>
    suspend fun start()
    suspend fun stop()
}
