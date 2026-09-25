package com.neop2p.di

import com.neop2p.data.network.ExplorerPins
import com.neop2p.data.network.TorState
import com.neop2p.data.tor.TorHttpPolicy
import okhttp3.CertificatePinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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

    // M-5: the Tor rewrite must not drop the SPKI pinner — a direct or Tor
    // request to an explorer/price host is still pinned.
    @Test fun okHttpClientKeepsCertificatePinner() {
        val policy = TorHttpPolicy({ false }, { TorState.Disabled })
        val client = AppModule.buildOkHttpClient(policy)
        assertNotSame(CertificatePinner.DEFAULT, client.certificatePinner)
        assertEquals(ExplorerPins.pinSpecs().size, client.certificatePinner.pins.size)
        assertTrue(client.certificatePinner.findMatchingPins("mempool.space").isNotEmpty())
    }
}
