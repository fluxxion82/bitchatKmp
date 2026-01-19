package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.model.ChannelMember
import com.bitchat.domain.chat.repository.ChatRepository

class GetChannelMembers(
    private val chatRepository: ChatRepository,
) : Usecase<String, List<ChannelMember>> {
    override suspend fun invoke(param: String): List<ChannelMember> {
        return chatRepository.getChannelMembers(param)
    }
}
