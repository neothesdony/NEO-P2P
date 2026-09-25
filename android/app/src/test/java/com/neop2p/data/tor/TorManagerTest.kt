package com.neop2p.data.tor

import com.neop2p.data.network.TorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorManagerTest {
    private class FakeTorSettings(private var enabledState: Boolean = false) : TorSettings {
        override fun isEnabled(): Boolean = enabledState
        override fun setEnabled(enabled: Boolean) { enabledState = enabled }
    }

    private class FakeTorControl : TorControl {
        private val flow = MutableSharedFlow<TorControlEvent>(extraBufferCapacity = 16)
        override val events: Flow<TorControlEvent> = flow
        var started = false
        var stopped = false
        override suspend fun start() { started = true }
        override suspend fun stop() { stopped = true }
        suspend fun emit(event: TorControlEvent) { flow.emit(event) }
    }

    @Test fun disabledAtStartup() = runTest {
        val manager = TorManager(FakeTorSettings(false), FakeTorControl(), backgroundScope)
        assertEquals(TorState.Disabled, manager.state.value)
    }

    @Test fun enabledAtStartupBeginsStartingNotConnected() = runTest {
        val manager = TorManager(FakeTorSettings(true), FakeTorControl(), backgroundScope)
        assertEquals(TorState.Starting, manager.state.value)
    }

    @Test fun enablingStartsAndReachesConnected() = runTest {
        val settings = FakeTorSettings(false)
        val control = FakeTorControl()
        val manager = TorManager(settings, control, backgroundScope)
        manager.setEnabled(true)
        runCurrent()
        assertTrue(settings.isEnabled())
        assertTrue(control.started)

        control.emit(TorControlEvent.Bootstrap(50))
        runCurrent()
        assertEquals(TorState.Bootstrapping(50), manager.state.value)

        control.emit(TorControlEvent.Ready(8118))
        runCurrent()
        assertEquals(TorState.Connected(8118), manager.state.value)
    }

    @Test fun failureIsSurfaced() = runTest {
        val control = FakeTorControl()
        val m2 = TorManager(FakeTorSettings(true), control, backgroundScope)
        runCurrent()
        control.emit(TorControlEvent.Failure("boom"))
        runCurrent()
        assertEquals(TorState.Failed("boom"), m2.state.value)
    }

    @Test fun disablingStopsAndReturnsToDisabled() = runTest {
        val settings = FakeTorSettings(true)
        val control = FakeTorControl()
        val manager = TorManager(settings, control, backgroundScope)
        manager.setEnabled(false)
        runCurrent()
        assertFalse(settings.isEnabled())
        assertTrue(control.stopped)
        assertEquals(TorState.Disabled, manager.state.value)
    }
}
