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

        // Verify fee wallet integrity at startup
        // If someone forked the code and changed the fee address, this logs a CRITICAL warning
        NeoP2PConfig.verifyFeeWalletIntegrity()
    }
}
