package com.neop2p.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.p2p.P2POrchestrator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * C11 (2026-09-23): expedited post-reboot re-arm. Starts the always-on P2P
 * service, brings the orchestrator up (idempotent), and runs one deadline
 * sweep so escrows that expired while the device was off are actioned.
 */
@HiltWorker
class DeadlineRearmWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: P2POrchestrator,
    private val escrowService: EscrowService
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Best-effort: Android 12+ may refuse a background FGS start, and that
        // must not fail the sweep below.
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, P2PBackgroundService::class.java)
            )
        }.onFailure { Log.w(TAG, "Could not start P2P service from re-arm worker: ${it.message}") }

        return try {
            orchestrator.start()
            escrowService.expireStaleEscrows()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Deadline re-arm sweep failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DeadlineRearmWorker"
    }
}
