package com.neop2p.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.neop2p.data.p2p.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Foreground service that maintains NEO-P2P background connections.
 *
 * Keeps the libp2p host, Nostr WebSocket, and WebRTC connections alive
 * even when the app is minimized. This is critical for peers behind NAT
 * who need their p2p endpoints to stay reachable.
 */
@AndroidEntryPoint
class P2PBackgroundService : Service() {

    companion object {
        private const val TAG = "P2PBackgroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "neop2p_connections"
        private const val CHANNEL_NAME = "NEO-P2P Connections"
    }

    @Inject lateinit var orchestrator: P2POrchestrator
    @Inject lateinit var notificationDispatcher: NotificationDispatcher

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        isRunning = true
        val notification = buildNotification()
        // specialUse (not dataSync): Android 15+ caps dataSync FGS at 6h/day, which
        // would kill the always-on P2P connection and any local notifications.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        scope.launch {
            try {
                orchestrator.start()
                Log.d(TAG, "P2P background service started")
            } catch (e: com.neop2p.data.p2p.IdentityLockedException) {
                // P0-4: the identity seed is gated behind device auth and the
                // unlock window expired — P2P is paused until the user opens
                // the app and unlocks. Surface it instead of failing silently.
                Log.w(TAG, "Identity locked — P2P paused until unlock: ${e.message}")
                notificationDispatcher.notifyIdentityLocked()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start P2P service", e)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        try {
            kotlinx.coroutines.runBlocking { orchestrator.stop() }
        } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains NEO-P2P peer connections"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NEO-P2P")
            .setContentText("Connected to network")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
