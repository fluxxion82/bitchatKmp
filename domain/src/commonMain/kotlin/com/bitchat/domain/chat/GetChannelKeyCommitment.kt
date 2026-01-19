package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository

class GetChannelKeyCommitment(
    private val chatRepository: ChatRepository,
) : Usecase<String, String?> {
    override suspend fun invoke(param: String): String? {
        return chatRepository.getChannelKeyCommitment(param)
    }
}
