package com.bitchat.viewmodel.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.invoke
import com.bitchat.domain.chat.MarkPrivateChatRead
import com.bitchat.domain.chat.ObserveLatestUnreadPrivatePeer
import com.bitchat.domain.chat.ObservePrivateChats
import com.bitchat.domain.chat.ObserveSelectedPrivatePeer
import com.bitchat.domain.chat.ObserveUnreadPrivatePeers
import com.bitchat.domain.chat.SendMessage
import com.bitchat.domain.user.GetUserNickname
import com.bitchat.domain.user.GetUserState
import com.bitchat.viewvo.chat.DmState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DmViewModel(
    private val observePrivateChats: ObservePrivateChats,
    private val observeUnreadPrivatePeers: ObserveUnreadPrivatePeers,
    private val observeLatestUnreadPrivatePeer: ObserveLatestUnreadPrivatePeer,
    private val observeSelectedPrivatePeer: ObserveSelectedPrivatePeer,
    private val markPrivateChatRead: MarkPrivateChatRead,
    private val sendMessage: SendMessage,
    private val getUserState: GetUserState,
    private val getUserNickname: GetUserNickname
) : ViewModel() {

    private val _state = MutableStateFlow(DmState())
    val state: StateFlow<DmState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observePrivateChats().collect { chats ->
                _state.update { it.copy(privateChats = chats) }
            }
        }

        viewModelScope.launch {
            observeUnreadPrivatePeers().collect { unread ->
                _state.update { it.copy(unreadPeers = unread) }
            }
        }

        viewModelScope.launch {
            observeLatestUnreadPrivatePeer().collect { latest ->
                _state.update { it.copy(latestUnreadPeer = latest) }
            }
        }

        viewModelScope.launch {
            observeSelectedPrivatePeer().collect { peer ->
                _state.update { it.copy(selectedPeer = peer) }
                if (peer != null) {
                    markPrivateChatRead(peer)
                }
            }
        }
    }

    fun updateMessageInput(text: String) {
        _state.update { it.copy(messageInput = text) }
    }

    fun sendMessage(recipientNickname: String) {
        val content = _state.value.messageInput.trim()
        if (content.isEmpty()) return

        _state.update { it.copy(isSending = true) }

        viewModelScope.launch {
            try {
                val channel = when (val userState = getUserState(Unit)) {
                    is UserState.Active -> when (val active = userState.activeState) {
                        is ActiveState.Chat -> active.channel
                        else -> {
                            _state.update { it.copy(isSending = false) }
                            return@launch
                        }
                    }

                    else -> {
                        _state.update { it.copy(isSending = false) }
                        return@launch
                    }
                }

                val sender = getUserNickname(Unit).first()

                sendMessage(
                    SendMessage.Params(
                        content = content,
                        channel = channel,
                        sender = sender
                    )
                )
                _state.update { it.copy(isSending = false, messageInput = "") }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isSending = false, errorMessage = "Failed to send: ${e.message}")
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
