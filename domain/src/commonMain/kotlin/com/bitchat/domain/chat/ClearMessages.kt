package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel

class ClearMessages(
    private val chatRepository: ChatRepository,
) : Usecase<ClearMessages.Params, Unit> {
    data class Params(val channel: Channel)

    override suspend fun invoke(param: Params) {
        chatRepository.clearMessages(param.channel)
    }
}
