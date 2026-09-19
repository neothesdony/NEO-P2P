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

        // Build-time network becomes the runtime network before anything reads it.
        NeoP2PConfig.network = BuildConfig.NETWORK

        NeoLog.sink = { level, tag, message, throwable ->
            when (level) {
                NeoLog.Level.INFO -> android.util.Log.i(tag, message)
                NeoLog.Level.WARN ->
                    if (throwable != null) android.util.Log.wtf(tag, message, throwable)
                    else android.util.Log.wtf(tag, message)
            }
        }

        // Verify fee wallet integrity at startup
        // If someone forked the code and changed the fee address, this logs a CRITICAL warning
        NeoP2PConfig.verifyFeeWalletIntegrity()
        // Verify arbitrator pubkey integrity at startup (same owner-key scheme)
        // If someone forked the code and swapped the arbitrator key, this logs a CRITICAL warning
        NeoP2PConfig.verifyArbitratorIntegrity()
    }
}
