package com.neop2p

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.neop2p.service.UpdateCheckWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NeoTradeApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** Application-lifetime scope for startup warm-up work. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

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

        // Weekly release check (GitHub Releases). KEEP so re-launching the app
        // does not reset the 7-day cadence; NetworkType.CONNECTED defers when
        // offline. Advisory only — never downloads or installs an APK.
        val updateRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            updateRequest
        )

        // M-10: capture crashes locally so a beta tester can export one from
        // Settings. Redacted at write time; never uploaded.
        com.neop2p.service.CrashLogHandler.install(this)

        // M-7: Hilt providers are synchronous, so the first injection opens
        // SQLCipher on the caller's thread (the main thread on a cold start).
        // Warm it here so the first screen normally observes an already-open
        // database. getInstance keeps its single-flight runBlocking fallback.
        appScope.launch {
            runCatching { com.neop2p.data.local.AppDatabase.getInstance(this@NeoTradeApp) }
                .onFailure { android.util.Log.w("Startup", "database warm-up failed", it) }
        }
    }
}
