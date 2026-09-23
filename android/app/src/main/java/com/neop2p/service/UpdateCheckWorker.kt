package com.neop2p.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.neop2p.BuildConfig
import com.neop2p.data.update.UpdateCheckStore
import com.neop2p.data.update.UpdateChecker
import com.neop2p.data.update.UpdatePolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Weekly GitHub-Releases update check. Posts a single advisory notification
 * when a newer release exists, at most once per tag. Any fetch failure is a
 * silent no-op (never prompt on an unverified version) and never retries.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val updateChecker: UpdateChecker,
    private val updateCheckStore: UpdateCheckStore,
    private val notificationDispatcher: NotificationDispatcher
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val latest = updateChecker.fetchLatest() ?: return Result.success()
        if (!UpdatePolicy.shouldNotify(
                latest.tag,
                BuildConfig.VERSION_NAME,
                updateCheckStore.lastNotifiedTag()
            )
        ) {
            return Result.success()
        }
        notificationDispatcher.notifyUpdateAvailable(latest.tag, latest.htmlUrl)
        updateCheckStore.setLastNotifiedTag(latest.tag)
        Log.i(TAG, "Notified update ${latest.tag} (installed ${BuildConfig.VERSION_NAME})")
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "neop2p_update_check"
        private const val TAG = "UpdateCheckWorker"
    }
}
