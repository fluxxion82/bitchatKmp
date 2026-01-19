package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository

class GetGeohashParticipants(
    private val chatRepository: ChatRepository,
) : Usecase<String, Map<String, String>> {
    override suspend fun invoke(param: String): Map<String, String> {
        return chatRepository.getGeohashParticipants(param)
    }
}
