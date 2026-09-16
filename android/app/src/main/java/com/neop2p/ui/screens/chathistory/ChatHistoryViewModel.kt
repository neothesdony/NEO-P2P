package com.neop2p.ui.screens.chathistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.p2p.IdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The Home "Chats" list. Recomputes on ANY write to chat_messages or
 * trade_offers, so a new message (or a read-flag update) reorders/clears the
 * list live.
 */
@HiltViewModel
class ChatHistoryViewModel @Inject constructor(
    chatMessageDao: ChatMessageDao,
    offerDao: OfferDao,
    private val identityManager: IdentityManager
) : ViewModel() {

    val threads: StateFlow<List<ChatThread>> = combine(
        chatMessageDao.observeAllMessages(),
        offerDao.getAllOffers()
    ) { messages, offers ->
        val myId = runCatching { identityManager.getOrCreateIdentity().peerId }.getOrDefault("")
        chatThreads(messages, offers, myId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
