package com.neop2p.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.neop2p.MainActivity
import com.neop2p.R
import com.neop2p.navigation.Routes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process notification dispatcher for NEO-P2P.
 *
 * Centralizes channel creation and every notification the app posts, so all
 * event producers (chat, offer-matched, escrow, wallet) feed into one place.
 * Uses [NotificationManagerCompat] (side-channel — no FCM, no backend) and
 * honors the user's notification permission (never posts if disabled).
 *
 * Channels (users can mute per category on Android 8+):
 *  - chat:      IMPORTANCE_HIGH (message arrives), vibrate, per-offer groups.
 *  - trade:     IMPORTANCE_DEFAULT — offer matched / escrow transitions.
 *  - wallet:    IMPORTANCE_LOW — incoming BTC.
 *  - connections: IMPORTANCE_LOW — the always-on "Connected to network" FGS
 *                notification (kept separate from event notifications).
 *
 * Tap targets deep-link into the app via explicit [Intent]s carrying extras
 * (offerId / peerId / escrowId). [PendingIntent]s use FLAG_IMMUTABLE as
 * required by Android 16's hardened intent-redirection checks.
 */
@Singleton
class NotificationDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appForegroundTracker: AppForegroundTracker
) {
    companion object {
        const val CHANNEL_CHAT = "neop2p_chat"
        const val CHANNEL_TRADE = "neop2p_trade"
        const val CHANNEL_WALLET = "neop2p_wallet"
        const val CHANNEL_CONNECTIONS = "neop2p_connections"

        const val EXTRA_OFFER_ID = "neop2p_extra_offer_id"
        const val EXTRA_PEER_ID = "neop2p_extra_peer_id"
        const val EXTRA_ESCROW_ID = "neop2p_extra_escrow_id"

        // Fixed ids so updates replace rather than duplicate.
        private const val CHAT_BASE_ID = 2000
        private const val OFFER_MATCHED_ID = 3000
        private const val ESCROW_BASE_ID = 4000
        private const val WALLET_BASE_ID = 5000
        private const val IDENTITY_LOCKED_ID = 6000
        private const val TRANSPORT_DOWN_ID = 6001
    }

    private val notifier: NotificationManagerCompat
        get() = NotificationManagerCompat.from(context)

    /**
     * Post a notification. Permission is guarded by [canNotify] at every call
     * site (areNotificationsEnabled() reflects the POST_NOTIFICATIONS runtime
     * grant on Android 13+), so the lint check can be suppressed here.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        notifier.notify(id, notification)
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        fun channel(id: String, name: String, importance: Int, desc: String, badge: Boolean) {
            val c = NotificationChannel(id, name, importance).apply {
                this.description = desc
                setShowBadge(badge)
                enableVibration(importance >= NotificationManager.IMPORTANCE_HIGH)
            }
            manager.createNotificationChannel(c)
        }

        channel(CHANNEL_CHAT, "Messages", NotificationManager.IMPORTANCE_HIGH,
            "New chat messages from your trading peers", true)
        channel(CHANNEL_TRADE, "Trades & escrow", NotificationManager.IMPORTANCE_DEFAULT,
            "Offer matched and escrow status changes", true)
        channel(CHANNEL_WALLET, "Wallet", NotificationManager.IMPORTANCE_LOW,
            "Incoming Bitcoin to your wallet", true)
        channel(CHANNEL_CONNECTIONS, "Network status", NotificationManager.IMPORTANCE_LOW,
            "Always-on peer connection status", false)
    }

    private fun canNotify(): Boolean {
        return try {
            notifier.areNotificationsEnabled()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Build a content [PendingIntent] that launches the app and routes to a
     * screen via route + extras. FLAG_IMMUTABLE per Android 16 guidance.
     */
    private fun contentIntent(route: String, extra: Pair<String, String>): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(Intent.EXTRA_TEXT, route) // deep-link route string
            putExtra(extra.first, extra.second)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            extra.second.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Notify a new inbound chat message for [offerId] from [peerId].
     * Suppression for the foreground/open-conversation case is the caller's
     * responsibility (see P2POrchestrator); this method only posts.
     */
    fun notifyChat(
        offerId: String,
        peerId: String,
        senderLabel: String,
        message: String
    ) {
        if (!canNotify()) return
        val id = chatId(offerId)
        val groupKey = "chat_$offerId"
        // P0 privacy: lock-screen must not leak plaintext. Title is generic
        // when the app is backgrounded (senderLabel may be visible on
        // unlocked shade, so we collapse to "Pesan baru" when backgrounded).
        // Body is always redacted — content lives in the app via E2EE.
        // Deep link carries offerId/peerId for navigation after unlock.
        val isForeground = appForegroundTracker.isForeground.value
        val title = if (isForeground) senderLabel.ifBlank { context.getString(R.string.notif_new_message) }
        else context.getString(R.string.notif_new_message)
        val redactedBody = context.getString(R.string.notif_chat_redacted)
        val notif = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(redactedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(redactedBody))
            .setGroup(groupKey)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_CHAT)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(context.getString(R.string.notif_new_message))
                    .setContentText(redactedBody)
                    .build()
            )
            .setContentIntent(contentIntent(Routes.chat(offerId, peerId), EXTRA_OFFER_ID to offerId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        post(id, notif)
    }

    /** Someone accepted / matched one of your offers (LXMF offer_status). */
    fun notifyOfferMatched(offerId: String, matchedPeerId: String) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CHANNEL_TRADE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notif_offer_matched_title))
            .setContentText(context.getString(R.string.notif_offer_matched_body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(Routes.offerDetail(offerId), EXTRA_OFFER_ID to offerId))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        post(OFFER_MATCHED_ID, n)
    }

    /**
     * The identity seed is gated behind device auth and the unlock window
     * expired — P2P is paused until the user opens the app and unlocks.
     * Posted from the background service so the outage is not silent.
     */
    fun notifyIdentityLocked() {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CHANNEL_TRADE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notif_identity_locked_title))
            .setContentText(context.getString(R.string.notif_identity_locked_body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(Routes.HOME, EXTRA_OFFER_ID to ""))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        post(IDENTITY_LOCKED_ID, n)
    }

    /**
     * The RNS transport failed to start for a non-lock reason (dead transport
     * node / unreachable network). Posted from the background service so the
     * outage is not silent — the 60s sweep keeps retrying in the background.
     */
    fun notifyTransportDown() {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CHANNEL_TRADE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notif_transport_down_title))
            .setContentText(context.getString(R.string.notif_transport_down_body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(Routes.HOME, EXTRA_OFFER_ID to ""))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        post(TRANSPORT_DOWN_ID, n)
    }

    /** Escrow lifecycle transition (funded / signed / released / disputed / refunded / cancelled).
     *
     * Ongoing funding states ("created" / "funding" / "funded") render as an Android 16
     * Live Update ([Notification.ProgressStyle]) with a progress bar + status chip, because
     * they represent an active, user-initiated, time-sensitive journey (awaiting seller
     * funding, then the 30-min funding window, then the 2-h auto-refund window). Terminal
     * states (signed / released / disputed / resolving / refunded / cancelled) post a normal
     * auto-cancel notification.
     */
    fun notifyEscrow(escrowId: String, status: String, title: String, message: String) {
        if (!canNotify()) return
        // Suppress while the user is actively in the app — escrow state is
        // reflected live on the screens they're looking at.
        if (appForegroundTracker.isForeground.value) return
        val id = ESCROW_BASE_ID + (escrowId.hashCode() and 0x7fffffff) % 0x1000
        val ongoing = status in setOf("created", "funding", "funded")

        if (ongoing && Build.VERSION.SDK_INT >= 36) {
            // Android 16+ promoted Live Update: progress bar + status chip.
            // NOTE: Notification.ProgressStyle is a platform class (no compat
            // wrapper), so build with the platform Notification.Builder on API 36+.
            val progressStyle = Notification.ProgressStyle().apply {
                addProgressPoint(Notification.ProgressStyle.Point(0).setColor(Color.rgb(96, 125, 139)))
                addProgressPoint(Notification.ProgressStyle.Point(100).setColor(Color.rgb(102, 187, 106)))
                addProgressSegment(Notification.ProgressStyle.Segment(100).setColor(0xFFFFC107.toInt()))
                setProgress(if (status == "funded") 70 else 15)
                setStyledByProgress(false)
            }
            val notif = Notification.Builder(context, CHANNEL_TRADE)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(progressStyle)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent(Routes.escrow(escrowId), EXTRA_ESCROW_ID to escrowId))
                .build()
            // NOTE: status-chip promotion needs EXTRA_REQUEST_PROMOTED_ONGOING /
            // setRequestPromotedOngoing, which aren't resolvable in core 1.15.0;
            // the ProgressStyle still renders a progress-centric notification with
            // upgraded drawer ranking on Android 16+. (Priority is governed by the
            // channel's importance on API 26+, so no setPriority needed here.)
            post(id, notif)
            return
        }

        val n = NotificationCompat.Builder(context, CHANNEL_TRADE)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(Routes.escrow(escrowId), EXTRA_ESCROW_ID to escrowId))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        post(id, n)
    }

    /** Incoming Bitcoin receive on the personal wallet. */
    fun notifyWalletReceive(txid: String, sats: Long) {
        if (!canNotify()) return
        // Suppress while the user is actively in the app — the wallet screen
        // and home balance reflect incoming funds live.
        if (appForegroundTracker.isForeground.value) return
        val id = WALLET_BASE_ID + (txid.hashCode() and 0x7fffffff) % 0x1000
        val btc = sats / 100_000_000.0
        val n = NotificationCompat.Builder(context, CHANNEL_WALLET)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notif_wallet_received_title))
            .setContentText(String.format(java.util.Locale.US, "%.8f BTC", btc))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(Routes.WALLET, EXTRA_OFFER_ID to ""))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        post(id, n)
    }

    /** Remove a notification by its fixed id (e.g. when a conversation is opened). */
    fun cancelChat(offerId: String) {
        notifier.cancel(chatId(offerId))
    }

    private fun chatId(offerId: String): Int =
        CHAT_BASE_ID + (offerId.hashCode() and 0x7fffffff) % 1000
}
