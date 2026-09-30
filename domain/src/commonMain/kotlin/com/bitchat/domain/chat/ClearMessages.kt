package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel

/** Empties a conversation: its messages go, and so does the command feedback filed under it. */
class ClearMessages(
    private val chatRepository: ChatRepository,
    private val chatNotices: ChatNotices,
) : Usecase<ClearMessages.Params, Unit> {
    data class Params(val channel: Channel)

    override suspend fun invoke(param: Params) {
        chatRepository.clearMessages(param.channel)
        chatNotices.clear(param.channel)
    }
}
