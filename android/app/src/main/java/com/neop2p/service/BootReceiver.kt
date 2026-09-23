package com.neop2p.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * C11 (2026-09-23): a reboot drops every in-memory deadline timer, so a funded
 * escrow could sit past its window with nothing to sweep it. On boot this
 * re-arms the deadline sweep once through an expedited worker — an FGS must not
 * be started directly from a broadcast (Android 12+ restriction).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!shouldRearm(intent?.action)) return
        val request = OneTimeWorkRequestBuilder<DeadlineRearmWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "neop2p_deadline_rearm"

        fun shouldRearm(action: String?): Boolean = action == Intent.ACTION_BOOT_COMPLETED
    }
}
