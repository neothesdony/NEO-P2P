package com.neop2p.di

import com.neop2p.data.network.TorState
import com.neop2p.data.tor.TorHttpPolicy
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModuleTorTest {
    @Test fun okHttpClientUsesTorPolicy() {
        val policy = TorHttpPolicy({ false }, { TorState.Disabled })
        val client = AppModule.buildOkHttpClient(policy)
        assertSame(policy, client.proxySelector)
        assertTrue(client.interceptors.contains(policy))
    }
}
