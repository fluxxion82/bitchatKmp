package com.bitchat.viewmodel.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.base.invoke
import com.bitchat.domain.chat.MarkPrivateChatRead
import com.bitchat.domain.chat.ObserveLatestUnreadPrivatePeer
import com.bitchat.domain.chat.ObservePrivateChats
import com.bitchat.domain.chat.ObserveSelectedPrivatePeer
import com.bitchat.domain.chat.ObserveUnreadPrivatePeers
import com.bitchat.domain.chat.SendMessage
import com.bitchat.domain.user.GetUserNickname
import com.bitchat.domain.location.model.Channel
import com.bitchat.viewvo.chat.DmState
import kotlinx.coroutines.CancellationException
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

    /**
     * Sends [text] to [channel], the DM the caller is showing, captured when the user pressed send.
     * The destination is never looked up later (the active chat can change in between, and a DM
     * line must not go to whatever chat is active by then), so this is the only way to send.
     * Anything but a mesh or Nostr DM (null, the mesh, a location or named channel, any Meshtastic
     * channel) is refused at once with an error and nothing is sent. Returns whether the line was
     * taken, so the caller keeps its draft when it was not. Leaves [DmState.messageInput] alone.
     */
    fun sendTo(channel: Channel?, text: String): Boolean {
        val content = text.trim()
        if (content.isEmpty()) return false
        refusal(channel)?.let { reason ->
            _state.update { it.copy(errorMessage = reason) }
            return false
        }
        val destination = channel ?: return false
        _state.update { it.copy(isSending = true) }
        viewModelScope.launch { send(content, destination) }
        return true
    }

    private suspend fun send(content: String, channel: Channel) {
        try {
            val sender = getUserNickname(Unit).first()
            sendMessage(
                SendMessage.Params(
                    content = content,
                    channel = channel,
                    sender = sender
                )
            )
            _state.update { it.copy(isSending = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(isSending = false, errorMessage = "Failed to send: ${e.message}")
            }
        }
    }

    /** Why a DM line must not be sent to [channel], or null when it may. */
    private fun refusal(channel: Channel?): String? = when (channel) {
        null -> "Not sent: no private conversation is open"
        is Channel.MeshDM, is Channel.NostrDM -> null
        // ChatRepo sends Meshtastic text as a broadcast whatever the node: refused until LoRa DMs exist.
        is Channel.Meshtastic ->
            if (channel.nodeNum != null) "Not sent: LoRa DMs are not supported yet" else "Not sent: not a private conversation"
        Channel.Mesh, is Channel.Location, is Channel.NamedChannel -> "Not sent: not a private conversation"
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
