package com.neop2p.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the app process is in the foreground.
 *
 * The [NotificationDispatcher] uses this to suppress notifications while the
 * user is actively looking at the app (e.g. don't ping them about a chat
 * message while they're already in that conversation).
 *
 * `isForeground` is `true` while any Activity is resumed (the app process has
 * at least one visible lifecycle owner). It flips to `false` once the last
 * activity leaves the resumed state — including when the user locks the phone,
 * so crypto trades still notify on the lock screen.
 *
 * [openConversationKey] is a best-effort hint for which conversation (offer
 * id) is currently open; the dispatcher uses it to suppress chat notifications
 * only for that specific conversation.
 */
@Singleton
class AppForegroundTracker @Inject constructor() : DefaultLifecycleObserver {

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** Offer id of the conversation currently open on screen ("" if none). */
    private val _openConversationKey = MutableStateFlow("")
    val openConversationKey: StateFlow<String> = _openConversationKey.asStateFlow()

    init {
        // Register the observer so ON_START / ON_STOP flip [isForeground].
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun setOpenConversation(offerId: String) {
        _openConversationKey.value = offerId
    }

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
    }
}
