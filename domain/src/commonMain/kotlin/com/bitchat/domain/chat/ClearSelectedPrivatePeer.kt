package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository

/**
 * Clears the chat repository's selected private peer if it is [param] (a peer ID), and leaves any
 * other alone. Repairs a DM switch left half applied: the peer selected while the user state says
 * a public chat, which makes that peer's DMs count as read.
 */
class ClearSelectedPrivatePeer(
    private val chatRepository: ChatRepository,
) : Usecase<String, Unit> {
    override suspend fun invoke(param: String) {
        if (chatRepository.getSelectedPrivatePeer() == param) chatRepository.setSelectedPrivatePeer(null)
    }
}
