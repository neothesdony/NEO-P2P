package com.neop2p

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NeoTradeApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    companion object {
        lateinit var instance: NeoTradeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // WebRTC requires an application context for PeerConnectionFactory initialization.
        // Must be called before any WebRTCManager usage.
        com.neop2p.data.p2p.initWebRTCContext(applicationContext)

        // Verify fee wallet integrity at startup
        // If someone forked the code and changed the fee address, this logs a CRITICAL warning
        NeoP2PConfig.verifyFeeWalletIntegrity()
        // Verify arbitrator pubkey integrity at startup (same owner-key scheme)
        // If someone forked the code and swapped the arbitrator key, this logs a CRITICAL warning
        NeoP2PConfig.verifyArbitratorIntegrity()
    }
}
