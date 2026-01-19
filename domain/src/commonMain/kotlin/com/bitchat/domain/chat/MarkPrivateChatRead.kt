package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository

class MarkPrivateChatRead(
    private val chatRepository: ChatRepository,
) : Usecase<String, Unit> {
    override suspend fun invoke(param: String) {
        chatRepository.markPrivateChatRead(param)
    }
}
