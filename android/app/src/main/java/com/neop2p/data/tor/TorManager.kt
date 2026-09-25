package com.neop2p.data.tor

import com.neop2p.data.network.TorState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
class TorManager @Inject constructor(
    private val settings: TorSettings,
    private val control: TorControl,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TorState>(
        if (settings.isEnabled()) TorState.Starting else TorState.Disabled
    )
    val state: StateFlow<TorState> = _state.asStateFlow()

    private var collectJob: Job? = null
    private var startJob: Job? = null

    init {
        if (settings.isEnabled()) start()
    }

    fun setEnabled(enabled: Boolean) {
        settings.setEnabled(enabled)
        if (enabled) start() else stop()
    }

    fun retry() {
        if (settings.isEnabled()) start()
    }

    private fun start() {
        collectJob?.cancel()
        startJob?.cancel()
        _state.value = TorState.Starting
        collectJob = scope.launch {
            control.events.collect { event -> _state.value = event.toState() }
        }
        startJob = scope.launch { control.start() }
    }

    private fun stop() {
        collectJob?.cancel()
        startJob?.cancel()
        scope.launch { control.stop() }
        _state.value = TorState.Disabled
    }
}

private fun TorControlEvent.toState(): TorState = when (this) {
    is TorControlEvent.Bootstrap -> TorState.Bootstrapping(percent)
    is TorControlEvent.Ready -> TorState.Connected(httpPort)
    is TorControlEvent.Failure -> TorState.Failed(reason)
}
